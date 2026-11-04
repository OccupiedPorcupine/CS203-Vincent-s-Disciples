"""Runs the voice assistant evaluation set (cases.json) against a running backend.

    python3 assistant-eval/run_eval.py                       # backend on http://localhost:8080
    python3 assistant-eval/run_eval.py --api http://localhost:8090 --only zh

Each case is sent as a new conversation from a dedicated account (eval@chicky.test by default),
and the reply is scored: the proposed sales must match exactly, questions and refusals must not
propose anything, and how-to questions must start a tour. Proposals are always cancelled, never
confirmed, and at the end the script checks that no sales were saved at all, which is the
assistant's main guarantee. If audio/<case id>.webm (or .m4a/.wav/.mp3) exists, it is
transcribed first and the transcript is used instead of the case text.

Writes a Markdown report and the raw results to assistant-eval/results/. Uses only the
standard library. With the OpenAI provider a full run is roughly 80 messages; the backend
allows 20 a minute per user, so the script waits when it is rate limited.
"""

import argparse
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "scripts"))
from seed_demo_data import Api, sign_in  # noqa: E402

SINGAPORE = timezone(timedelta(hours=8))
AUDIO_TYPES = {".webm": "audio/webm", ".m4a": "audio/mp4", ".wav": "audio/wav", ".mp3": "audio/mpeg"}


def normalize(name):
    return " ".join(unicodedata.normalize("NFKC", name).lower().split())


def resolve_date(token, today):
    if token == "today":
        return today
    if token == "yesterday":
        return today - timedelta(days=1)
    if token == "tomorrow":
        return today + timedelta(days=1)
    if token.startswith("today-"):
        return today - timedelta(days=int(token.split("-")[1]))
    if token == "last-monday":
        return today - timedelta(days=today.weekday() or 7)
    raise ValueError(f"Unknown date {token}")


class EvalApi(Api):
    """Api that waits and retries when the assistant's rate limit is hit."""

    def request(self, method, path, body=None):
        while True:
            try:
                return super().request(method, path, body)
            except urllib.error.HTTPError as error:
                if error.code != 429:
                    raise
                print("  rate limited; waiting a minute", flush=True)
                time.sleep(61)

    def upload_audio(self, path):
        csrf = super().request("GET", "/api/csrf")
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"{path.name}\"\r\n"
                f"Content-Type: {AUDIO_TYPES[path.suffix]}\r\n\r\n").encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        request = urllib.request.Request(self.base_url + "/api/assistant/transcribe", data=body, method="POST", headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
            csrf["headerName"]: csrf["token"],
        })
        with self.opener.open(request) as response:
            return json.loads(response.read())


def ensure_dishes(api, names):
    existing = {dish["name"]: dish["id"] for dish in api.request("GET", "/api/dishes")}
    for name in names:
        if name not in existing:
            existing[name] = api.request("POST", "/api/dishes", {"name": name})["id"]
    return existing


def entry_key(entry):
    dish = f"new:{normalize(entry['newDishName'])}" if entry.get("newDish") else normalize(entry["dishName"])
    return dish, entry["date"], (entry["quantity"], entry.get("soldOutAt"), entry.get("leftoverQuantity"))


# The quantity must match, and so must a sold-out time and leftovers: given when expected, absent otherwise.
def expected_key(entry, today):
    dish = entry["dish"]
    dish = dish if dish.startswith("new:") else normalize(dish)
    return dish, resolve_date(entry["date"], today).isoformat(), (entry["quantity"], entry.get("soldOutAt"), entry.get("leftover"))


def seed_history(api, dishes, history, today):
    """Records history["sameWeekday"] on the same weekday as the forecast date in past weeks (oldest
    first), before the no-writes count starts, so the explain cases have a real forecast to ask about.
    Returns the numbers a reply may quote: the forecast, its range, the sales and the dates' day numbers."""
    dish_id = dishes[history["dish"]]
    target = resolve_date(history["date"], today)
    quantities = history["sameWeekday"]
    days = [target - timedelta(weeks=len(quantities) - index) for index in range(len(quantities))]
    recorded = {sale["date"] for sale in api.request("GET", f"/api/dishes/{dish_id}/sales")}
    missing = [{"date": day.isoformat(), "quantitySold": quantity}
               for day, quantity in zip(days, quantities) if day.isoformat() not in recorded]
    if missing:
        api.request("POST", f"/api/dishes/{dish_id}/sales", {"sales": missing})
    forecast = api.request("POST", "/api/forecast", {"dishId": dish_id, "forecastDate": target.isoformat()})
    allowed = {round(forecast["predictedDemand"]), forecast["low"], forecast["high"], *quantities}
    allowed |= {day.day for day in [*days, target]} | {target.year, target.month}
    # Counts and the range's rule the explanation may give: "your last 4 Fridays", "7 days", "at
    # least 28", "a rough 25% either side", "the middle 80% of misses".
    allowed |= set(range(1, 8)) | {28, 25, 80}
    return {"dishId": dish_id, "date": target.isoformat(), "allowed": allowed, "method": forecast["method"]}


# Numbers in a reply, without the ones inside dates and times ("2026-11-06", "12:40").
def numbers_in(text):
    text = re.sub(r"\d{4}-\d{2}-\d{2}|\d{1,2}:\d{2}", " ", text)
    return {round(float(number)) for number in re.findall(r"\d+(?:\.\d+)?", text)}


def closure_keys(closures):
    return {(closure["date"], closure["closed"]) for closure in closures}


def same_dish(expected_dish, actual_dish):
    # A new dish only needs to contain the expected name ("laksa" vs "Laksa noodles").
    if expected_dish.startswith("new:"):
        return actual_dish.startswith("new:") and expected_dish[4:] in actual_dish
    return expected_dish == actual_dish


def score(case, result, today, shown=None):
    expect = case["expect"]
    proposal, guide = result["proposal"], result["guide"]
    fields = None

    # Closed-day changes must match exactly; a sales-only case must not propose any.
    closures_ok = closure_keys((proposal or {}).get("closures") or []) == {
        (resolve_date(closure["date"], today).isoformat(), closure["closed"]) for closure in expect.get("closures", [])
    }

    if expect["type"] == "closure":
        passed = proposal is not None and not proposal.get("entries") and closures_ok
    elif expect["type"] == "sales":
        actual = [entry_key(entry) for entry in (proposal or {}).get("entries") or []]
        wanted = [expected_key(entry, today) for entry in expect["entries"]]
        fields = {"entries": len(wanted), "dish": 0, "date": 0, "quantity": 0}
        for dish, date, quantity in wanted:
            dish_matches = [a for a in actual if same_dish(dish, a[0])]
            fields["dish"] += bool(dish_matches)
            fields["date"] += any(a[1] == date for a in dish_matches)
            fields["quantity"] += any(a[1] == date and a[2] == quantity for a in dish_matches)
        passed = len(actual) == len(wanted) and fields["quantity"] == len(wanted) and closures_ok
    elif expect["type"] == "price":
        change = (proposal or {}).get("priceChange") or {}
        passed = (bool(change) and same_dish(expect["dish"], change["dishName"])
                  and abs(float(change["price"]) - expect["price"]) < 0.005
                  and ("unitCost" not in expect or (change["unitCost"] is not None and abs(float(change["unitCost"]) - expect["unitCost"]) < 0.005))
                  and closures_ok)
    elif expect["type"] in ("question", "refuse"):
        passed = proposal is None and not guide
    elif expect["type"] == "explain":
        # Only the forecast's real inputs: every number in the reply comes from them, no reason it
        # didn't use is named, and the reply says what `require` matches.
        problems = []
        invented = numbers_in(result["reply"]) - shown["allowed"]
        if invented:
            problems.append(f"numbers not in the inputs: {sorted(invented)}")
        problems += [f"named /{word}/" for word in expect.get("forbid", []) if re.search(word, result["reply"], re.IGNORECASE)]
        if "require" in expect and not re.search(expect["require"], result["reply"], re.IGNORECASE):
            problems.append(f"doesn't match /{expect['require']}/")
        if proposal or guide:
            problems.append("proposed something or started a tour")
        result["explainProblems"] = problems
        passed = not problems
    elif expect["type"] == "guide":
        passed = bool(guide) and any(step["guideId"] in expect["anyOf"] for step in guide)
    else:
        passed = True
    return passed, fields


def run_case(api, case, shown=None):
    turns = case.get("turns") or [case["text"]]
    audio = next((HERE / "audio" / f"{case['id']}{ext}" for ext in AUDIO_TYPES if (HERE / "audio" / f"{case['id']}{ext}").exists()), None)
    conversation_id, response, transcript, proposal_ids = None, None, None, []
    started = time.monotonic()

    for number, text in enumerate(turns):
        input_mode = "text"
        if audio and number == 0:
            transcription = api.upload_audio(audio)
            text, transcript, input_mode = transcription["text"], transcription["text"], "voice"
        response = api.request("POST", "/api/assistant/messages", {
            "conversationId": conversation_id, "text": text, "page": case.get("page", "dashboard"),
            "inputMode": input_mode,
            "forecast": {"dishId": shown["dishId"], "date": shown["date"]} if shown and case.get("page") == "forecast" else None,
        })
        conversation_id = response["conversationId"]
        if response.get("proposal"):
            proposal_ids.append(response["proposal"]["id"])

    for proposal_id in proposal_ids:
        api.request("POST", f"/api/assistant/proposals/{proposal_id}/cancel")

    return {
        "reply": response["reply"],
        "proposal": response.get("proposal"),
        "guide": response.get("guide"),
        "transcript": transcript,
        "seconds": round(time.monotonic() - started, 2),
    }


def report(results, status, today, writes, path, stopped_after=None):
    by_category = defaultdict(list)
    for result in results:
        by_category[result["category"]].append(result)

    lines = [
        f"# Assistant evaluation, {today.isoformat()}",
        "",
        f"Model: `{status['model']}` · voice available: {status['voice']} · cases: {len(results)}",
        "",
        *([f"**Incomplete:** stopped after {stopped_after} cases because the OpenAI account reached its usage limit.", ""]
          if stopped_after is not None else []),
        "| Category | Passed | Dish | Date | Quantity | Avg seconds |",
        "|---|---|---|---|---|---|",
    ]
    totals = defaultdict(int)
    for category, items in by_category.items():
        passed = sum(item["passed"] for item in items)
        field_sums = defaultdict(int)
        for item in items:
            for key, value in (item["fields"] or {}).items():
                field_sums[key] += value
        entries = field_sums["entries"]
        pct = lambda key: f"{field_sums[key]}/{entries}" if entries else "–"
        avg = sum(item["seconds"] for item in items) / len(items)
        lines.append(f"| {category} | {passed}/{len(items)} | {pct('dish')} | {pct('date')} | {pct('quantity')} | {avg:.1f} |")
        totals["passed"] += passed
        totals["cases"] += len(items)
        for key in ("entries", "dish", "date", "quantity"):
            totals[key] += field_sums[key]
    lines.append(f"| **All** | **{totals['passed']}/{totals['cases']}** | {totals['dish']}/{totals['entries']} | "
                 f"{totals['date']}/{totals['entries']} | {totals['quantity']}/{totals['entries']} | |")
    lines += ["", f"**Sales or prices saved without confirmation: {writes}** (must be 0)", "", "## Failed cases", ""]

    for result in results:
        if not result["passed"]:
            got = (f"proposal {[entry_key(e) for e in result['proposal']['entries'] or []]}" if result["proposal"]
                   else "; ".join(result["explainProblems"]) if result.get("explainProblems")
                   else f"tour {[s['guideId'] for s in result['guide']]}" if result["guide"] else "no proposal or tour")
            heard = f" (heard: {result['transcript']})" if result["transcript"] else ""
            lines.append(f"- `{result['id']}` {result['input']}{heard} → {got}. Reply: {result['reply']}")
    if all(result["passed"] for result in results):
        lines.append("None.")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--email", default="eval@chicky.test")
    parser.add_argument("--password", default="chicky-eval-password")
    parser.add_argument("--only", help="run only cases whose id starts with this, e.g. zh or mix-0")
    args = parser.parse_args()

    data = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    cases = [case for case in data["cases"] if not args.only or case["id"].startswith(args.only)]
    api = EvalApi(args.api)
    sign_in(api, args.email, args.password, "Assistant Evaluation")

    status = api.request("GET", "/api/assistant/status")
    if not status["available"]:
        sys.exit("The assistant isn't available on this backend (set OPENAI_API_KEY or ASSISTANT_PROVIDER=offline).")

    dishes = ensure_dishes(api, data["dishes"])
    today = datetime.now(SINGAPORE).date()
    shown = seed_history(api, dishes, data["history"], today)
    sales_before = {name: len(api.request("GET", f"/api/dishes/{dish_id}/sales")) for name, dish_id in dishes.items()}
    prices_before = {name: len(api.request("GET", f"/api/dishes/{dish_id}/prices")) for name, dish_id in dishes.items()}
    results = []
    stopped = False

    for case in cases:
        print(f"{case['id']} ...", end=" ", flush=True)
        try:
            result = run_case(api, case, shown)
            passed, fields = score(case, result, today, shown)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            if "ASSISTANT_BUSY" in detail:
                # The OpenAI account's limit: every further case would fail the same way.
                print(f"stopped: {json.loads(detail)['message']}")
                stopped = True
                break
            # e.g. a 502 when one model call fails; counted as a failure and the run goes on.
            result = {"reply": f"HTTP {error.code}: {detail}", "proposal": None, "guide": None, "transcript": None, "seconds": 0}
            passed, fields = False, None
        result.update(id=case["id"], category=case["category"], input=" / ".join(case.get("turns") or [case["text"]]),
                      expected=case["expect"], passed=passed, fields=fields)
        results.append(result)
        print("pass" if passed else "FAIL")

    # Includes dishes the assistant may have been asked to create; none should exist either.
    all_dishes = {dish["name"]: dish["id"] for dish in api.request("GET", "/api/dishes")}
    writes = sum(len(api.request("GET", f"/api/dishes/{dish_id}/sales")) - sales_before.get(name, 0)
                 for name, dish_id in all_dishes.items())
    writes += len(all_dishes) - len(dishes)
    # Price proposals are cancelled too, so no price may change either.
    writes += sum(len(api.request("GET", f"/api/dishes/{dish_id}/prices")) - prices_before.get(name, 0)
                  for name, dish_id in all_dishes.items())

    stamp = datetime.now(SINGAPORE).strftime("%Y-%m-%d-%H%M")
    model = status["model"].replace("/", "-")
    out = HERE / "results"
    out.mkdir(exist_ok=True)
    (out / f"{stamp}-{model}.json").write_text(json.dumps(results, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    report(results, status, today, writes, out / f"{stamp}-{model}.md", stopped_after=len(results) if stopped else None)


if __name__ == "__main__":
    main()
