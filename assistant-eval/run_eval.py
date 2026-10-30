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
standard library. With the OpenAI provider a full run is roughly 50 messages; the backend
allows 20 a minute per user, so the script waits when it is rate limited.
"""

import argparse
import json
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
    return dish, entry["date"], entry["quantity"]


def expected_key(entry, today):
    dish = entry["dish"]
    dish = dish if dish.startswith("new:") else normalize(dish)
    return dish, resolve_date(entry["date"], today).isoformat(), entry["quantity"]


def closure_keys(closures):
    return {(closure["date"], closure["closed"]) for closure in closures}


def same_dish(expected_dish, actual_dish):
    # A new dish only needs to contain the expected name ("laksa" vs "Laksa noodles").
    if expected_dish.startswith("new:"):
        return actual_dish.startswith("new:") and expected_dish[4:] in actual_dish
    return expected_dish == actual_dish


def score(case, result, today):
    expect = case["expect"]
    proposal, guide = result["proposal"], result["guide"]
    fields = None

    # Closed-day changes must match exactly; a sales-only case must not propose any.
    closures_ok = closure_keys((proposal or {}).get("closures", [])) == {
        (resolve_date(closure["date"], today).isoformat(), closure["closed"]) for closure in expect.get("closures", [])
    }

    if expect["type"] == "closure":
        passed = proposal is not None and not proposal.get("entries") and closures_ok
    elif expect["type"] == "sales":
        actual = [entry_key(entry) for entry in (proposal or {}).get("entries", [])]
        wanted = [expected_key(entry, today) for entry in expect["entries"]]
        fields = {"entries": len(wanted), "dish": 0, "date": 0, "quantity": 0}
        for dish, date, quantity in wanted:
            dish_matches = [a for a in actual if same_dish(dish, a[0])]
            fields["dish"] += bool(dish_matches)
            fields["date"] += any(a[1] == date for a in dish_matches)
            fields["quantity"] += any(a[1] == date and a[2] == quantity for a in dish_matches)
        passed = len(actual) == len(wanted) and fields["quantity"] == len(wanted) and closures_ok
    elif expect["type"] in ("question", "refuse"):
        passed = proposal is None and not guide
    elif expect["type"] == "guide":
        passed = bool(guide) and any(step["guideId"] in expect["anyOf"] for step in guide)
    else:
        passed = True
    return passed, fields


def run_case(api, case):
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
            "conversationId": conversation_id, "text": text, "page": "dashboard",
            "inputMode": input_mode,
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
    lines += ["", f"**Sales saved without confirmation: {writes}** (must be 0)", "", "## Failed cases", ""]

    for result in results:
        if not result["passed"]:
            got = (f"proposal {[entry_key(e) for e in result['proposal']['entries']]}" if result["proposal"]
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
    sales_before = {name: len(api.request("GET", f"/api/dishes/{dish_id}/sales")) for name, dish_id in dishes.items()}
    today = datetime.now(SINGAPORE).date()
    results = []
    stopped = False

    for case in cases:
        print(f"{case['id']} ...", end=" ", flush=True)
        try:
            result = run_case(api, case)
            passed, fields = score(case, result, today)
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

    stamp = datetime.now(SINGAPORE).strftime("%Y-%m-%d-%H%M")
    model = status["model"].replace("/", "-")
    out = HERE / "results"
    out.mkdir(exist_ok=True)
    (out / f"{stamp}-{model}.json").write_text(json.dumps(results, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    report(results, status, today, writes, out / f"{stamp}-{model}.md", stopped_after=len(results) if stopped else None)


if __name__ == "__main__":
    main()
