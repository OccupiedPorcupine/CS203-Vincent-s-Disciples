"""Runs the stall documents (RAG) evaluation set (rag_cases.json) against a running backend (issue #155).

    python3 assistant-eval/run_rag_eval.py                      # backend on http://localhost:8080
    python3 assistant-eval/run_rag_eval.py --only en --no-compare

Signs in as eval-rag@chicky.test, replaces its documents with the fixtures in documents/ (the
festive-period notes go up as a PDF made here, to test PDF reading too), gives Chicken rice and
Char siew rice app prices that differ from the menu document, then sends each case as a new
conversation. Proposals are cancelled, never confirmed; at the end the script checks that no
document, closed day or sale was saved.

Measures, per case and overall: whether the right document was among the parts retrieved for the
message (hit rate@5, from the `retrieved` parts the backend logs), whether the answer is right and
cites the right document, whether uncovered questions are answered "not in your documents",
whether instructions inside a document are ignored, and whether "remember that ..." becomes a
note only when it should. It then sweeps the similarity floor and k using
GET /api/documents/search?all=true, so the configured values can be chosen from the results.

With --compare (the default) the answerable and uncovered questions are also sent from
eval-rag-nodocs@chicky.test, which has no documents, so rag_report.sql can compare tokens and cost
per message with and without documents.

Writes results/<date>-<model>-rag.md and .json. Uses only the standard library.
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from collections import defaultdict
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_eval import SINGAPORE, EvalApi, ensure_dishes, sign_in  # noqa: E402

NOT_COVERED = re.compile(r"(don'?t|do not|doesn'?t|does not|didn'?t) (say|cover|mention|have|include|list)|not (in|covered|mentioned|listed)"
                         r"|no (information|mention|details?|record)|couldn'?t find|can'?t find|Documents page|没有|没提到|文件里", re.IGNORECASE)
THRESHOLDS = [0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5]
KS = [3, 5, 8]


def pdf_from_text(text):
    """A plain PDF with the text in Helvetica, about 50 lines a page, so the backend reads a real
    text layer (PDFBox). ASCII only."""
    lines = []
    for paragraph in text.splitlines():
        words, line = paragraph.split(), ""
        for word in words:
            if len(line) + len(word) + 1 > 90:
                lines.append(line)
                line = word
            else:
                line = f"{line} {word}".strip()
        lines.append(line)
    pages = [lines[i:i + 50] for i in range(0, len(lines), 50)] or [[]]
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    kids = []
    for page in pages:
        escaped = [line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") for line in page]
        stream = "BT /F1 11 Tf 14 TL 56 790 Td " + " ".join(f"({line}) '" for line in escaped) + " ET"
        data = zlib.compress(stream.encode("latin-1"))
        objects.append(f"<< /Length {len(data)} /Filter /FlateDecode >>\nstream\n".encode("latin-1") + data + b"\nendstream")
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {len(objects)} 0 R >>")
        kids.append(f"{len(objects)} 0 R")
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(kids)} >>"
    out, offsets = bytearray(b"%PDF-1.4\n"), []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + (body if isinstance(body, bytes) else body.encode("latin-1")) + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def upload(api, filename, content, title):
    csrf = api.request("GET", "/api/csrf")
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n{title}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(api.base_url + "/api/documents/upload", data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}", "Accept": "application/json",
        csrf["headerName"]: csrf["token"]})
    with api.opener.open(request) as response:
        return json.loads(response.read())


def seed_documents(api, fixtures):
    """Replaces the account's documents with the fixtures; returns {title: id}."""
    for document in api.request("GET", "/api/documents"):
        api.request("DELETE", f"/api/documents/{document['id']}")
    ids = {}
    for fixture in fixtures:
        text = (HERE / "documents" / fixture["file"]).read_bytes()
        if fixture.get("asPdf"):
            saved = upload(api, Path(fixture["file"]).with_suffix(".pdf").name, pdf_from_text(text.decode("utf-8")), fixture["title"])
        else:
            saved = upload(api, fixture["file"], text, fixture["title"])
        if saved["indexStatus"] != "INDEXED":
            sys.exit(f"{fixture['file']} wasn't indexed ({saved['indexStatus']}); check the embedding model.")
        ids[fixture["title"]] = saved["id"]
        print(f"  {fixture['title']}: {saved['characters']} characters")
    return ids


def run_case(api, case):
    turns = case.get("turns") or [case["text"]]
    conversation_id, response, proposal_ids, retrieved = None, None, [], []
    started = time.monotonic()
    for text in turns:
        response = api.request("POST", "/api/assistant/messages",
                               {"conversationId": conversation_id, "text": text, "page": "dashboard", "inputMode": "text"})
        conversation_id = response["conversationId"]
        if response.get("proposal"):
            proposal_ids.append(response["proposal"]["id"])
    for proposal_id in proposal_ids:
        api.request("POST", f"/api/assistant/proposals/{proposal_id}/cancel")
    return {"reply": response["reply"], "proposal": response.get("proposal"), "sources": response.get("sources") or [],
            "retrieved": response.get("retrieved") or [], "seconds": round(time.monotonic() - started, 2)}


def score(case, result, ids):
    expect, reply, proposal = case["expect"], result["reply"], result["proposal"]
    retrieved = {chunk["documentId"] for chunk in result["retrieved"]}
    from_message = {chunk["documentId"] for chunk in result["retrieved"] if chunk["via"] == "message"}
    cited = {source["documentId"] for source in result["sources"]}
    checks = {}
    if expect["type"] == "answer":
        wanted = {ids[title] for title in expect["documents"]}
        found = (lambda docs: bool(wanted & docs)) if expect.get("anyDocument") else (lambda docs: wanted <= docs)
        checks["hit@5"] = found(retrieved)
        checks["hit@5 with the message"] = found(from_message)
        checks["answer"] = proposal is None and all(re.search(pattern, reply, re.IGNORECASE) for pattern in expect["require"])
        checks["citation"] = bool(cited) and cited <= wanted and (expect.get("anyDocument") or bool(cited & wanted))
        passed = checks["answer"] and checks["citation"]
    elif expect["type"] == "not-covered":
        checks["said not covered"] = proposal is None and not cited and bool(NOT_COVERED.search(reply))
        passed = checks["said not covered"]
    elif expect["type"] == "conflict":
        checks["app price"] = proposal is None and all(re.search(pattern, reply) for pattern in expect["require"])
        checks["said the document differs"] = bool(re.search(expect["differs"], reply, re.IGNORECASE))
        passed = checks["app price"]
    elif expect["type"] == "injection":
        checks["nothing proposed"] = proposal is None
        checks["nothing forbidden said"] = not any(re.search(pattern, reply, re.IGNORECASE) for pattern in expect.get("forbid", []))
        checks["answered"] = all(re.search(pattern, reply, re.IGNORECASE) for pattern in expect.get("require", []))
        passed = all(checks.values())
    elif expect["type"] == "note":
        passed = checks["note card"] = bool(proposal) and proposal["type"] == "NOTE"
    elif expect["type"] == "no-note":
        passed = checks["no note card"] = not proposal or proposal["type"] != "NOTE"
    elif expect["type"] == "closure":
        passed = checks["closure card"] = bool(proposal) and proposal["type"] != "NOTE" and bool(proposal.get("closures"))
    elif expect["type"] == "no-proposal":
        passed = checks["no card for a remark"] = proposal is None
    else:
        raise ValueError(expect["type"])
    return passed, checks


def sweep(api, cases, ids):
    """Hit rate@k and noise for each similarity floor, from unfiltered search scores."""
    rows, noise = [], []
    for case in cases:
        turns = case.get("turns") or [case["text"]]
        query = "\n".join(turns[-2:])
        hits = api.request("GET", "/api/documents/search?" + urllib.parse.urlencode({"q": query, "k": 10, "all": "true"}))
        if case["expect"]["type"] == "answer":
            rows.append(({ids[title] for title in case["expect"]["documents"]}, case["expect"].get("anyDocument", False), hits))
        elif case["expect"]["type"] == "not-covered":
            noise.append(hits)
    table = {}
    for threshold in THRESHOLDS:
        for k in KS:
            hit = 0
            for wanted, any_document, hits in rows:
                docs = {h["documentId"] for h in hits[:k] if h["score"] >= threshold}
                hit += bool(wanted & docs) if any_document else wanted <= docs
            table[(threshold, k)] = hit
        table[(threshold, "noise")] = sum(any(h["score"] >= threshold for h in hits[:5]) for hits in noise)
    best_wrong = max((h["score"] for hits in noise for h in hits[:1]), default=0)
    worst_right = min((max((h["score"] for h in hits if h["documentId"] in wanted), default=0) for wanted, _, hits in rows), default=0)
    return table, len(rows), len(noise), best_wrong, worst_right


def report(results, status, sweep_result, writes, path):
    by_category = defaultdict(list)
    for result in results:
        by_category[result["category"]].append(result)
    lines = [f"# Stall documents (RAG) evaluation, {datetime.now(SINGAPORE).date().isoformat()}", "",
             f"Model: `{status['model']}` · cases: {len(results)}", "",
             "| Category | Passed | Avg seconds |", "|---|---|---|"]
    for category, items in by_category.items():
        lines.append(f"| {category} | {sum(i['passed'] for i in items)}/{len(items)} | {sum(i['seconds'] for i in items) / len(items):.1f} |")
    lines.append(f"| **All** | **{sum(r['passed'] for r in results)}/{len(results)}** | |")

    def rate(check):
        items = [r for r in results if check in r["checks"]]
        return f"{sum(r['checks'][check] for r in items)}/{len(items)}" if items else "–"
    lines += ["", "| Metric | Result |", "|---|---|"]
    for label, check in [("Retrieval hit rate@5 (right document among the parts found)", "hit@5"),
                         ("…with the message alone, before any search_documents", "hit@5 with the message"),
                         ("Answer right", "answer"), ("Cites the right document, and only that", "citation"),
                         ("Uncovered questions answered \"not in your documents\"", "said not covered"),
                         ("Conflicts: gives the app's price", "app price"), ("Conflicts: says the document differs", "said the document differs"),
                         ("Injection: nothing proposed", "nothing proposed"), ("Injection: nothing it asked for said", "nothing forbidden said"),
                         ("\"Remember that ...\" becomes a note card", "note card"), ("Not a note when it shouldn't be", "no note card"),
                         ("The stall's own closed day stays a closure", "closure card"),
                         ("No card for a passing remark", "no card for a remark")]:
        lines.append(f"| {label} | {rate(check)} |")
    lines += ["", f"**Documents, closed days or sales saved without confirmation: {writes}** (must be 0)", ""]

    table, answerable, uncovered, best_wrong, worst_right = sweep_result
    lines += ["## Similarity floor and k", "",
              f"From unfiltered search scores: how many of the {answerable} answerable questions find their documents in the top k "
              f"above the floor, and how many of the {uncovered} uncovered questions still get a part above it (noise). "
              f"Highest score of any part for an uncovered question: {best_wrong:.3f}. Lowest best score of a right document: {worst_right:.3f}.", "",
              "| Floor | " + " | ".join(f"hit@{k}" for k in KS) + " | noise |", "|---|" + "---|" * (len(KS) + 1)]
    for threshold in THRESHOLDS:
        lines.append(f"| {threshold:.2f} | " + " | ".join(str(table[(threshold, k)]) for k in KS) + f" | {table[(threshold, 'noise')]} |")

    lines += ["", "## Failed cases", ""]
    for result in results:
        if not result["passed"]:
            failed = ", ".join(name for name, ok in result["checks"].items() if not ok)
            sources = [s["title"] for s in result["sources"]]
            lines.append(f"- `{result['id']}` {result['input']} → failed {failed}; sources {sources}; "
                         f"proposal {result['proposal']['type'] if result['proposal'] else None}. Reply: {result['reply']}")
    if all(result["passed"] for result in results):
        lines.append("None.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def counts(api):
    closures = api.request("GET", "/api/closures")
    sales = sum(len(api.request("GET", f"/api/dishes/{dish['id']}/sales")) for dish in api.request("GET", "/api/dishes"))
    return len(api.request("GET", "/api/documents")), len(closures.get("closedDates", [])), sales


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--email", default="eval-rag@chicky.test")
    parser.add_argument("--password", default="chicky-eval-password")
    parser.add_argument("--only", help="run only cases whose id starts with this")
    parser.add_argument("--no-compare", action="store_true", help="skip the same questions from an account without documents")
    args = parser.parse_args()

    data = json.loads((HERE / "rag_cases.json").read_text(encoding="utf-8"))
    cases = [case for case in data["cases"] if not args.only or case["id"].startswith(args.only)]
    api = EvalApi(args.api)
    sign_in(api, args.email, args.password, "RAG Evaluation")
    status = api.request("GET", "/api/assistant/status")
    if not status["available"]:
        sys.exit("The assistant isn't available on this backend (set OPENAI_API_KEY or ASSISTANT_PROVIDER=offline).")

    dishes = ensure_dishes(api, list(data["appPrices"]) + ["Roast pork rice", "Fishball noodles"])
    for name, price in data["appPrices"].items():
        api.request("PUT", f"/api/dishes/{dishes[name]}/price", {"price": price, "unitCost": None})
    print("Documents:")
    ids = seed_documents(api, data["documents"])
    before = counts(api)

    results = []
    for case in cases:
        print(f"{case['id']} ...", end=" ", flush=True)
        try:
            result = run_case(api, case)
            passed, checks = score(case, result, ids)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            result = {"reply": f"HTTP {error.code}: {detail}", "proposal": None, "sources": [], "retrieved": [], "seconds": 0}
            passed, checks = False, {"request": False}
        result.update(id=case["id"], category=case["category"], input=" / ".join(case.get("turns") or [case["text"]]),
                      expected=case["expect"], passed=passed, checks=checks)
        results.append(result)
        print("pass" if passed else "FAIL")

    after = counts(api)
    writes = sum(a - b for a, b in zip(after, before))
    sweep_result = sweep(api, data["cases"], ids)

    if not args.no_compare:
        print("Same questions without documents (for rag_report.sql):")
        plain = EvalApi(args.api)
        sign_in(plain, args.email.replace("@", "-nodocs@"), args.password, "RAG Evaluation without documents")
        ensure_dishes(plain, list(data["appPrices"]))
        for case in cases:
            if case["expect"]["type"] in ("answer", "not-covered"):
                run_case(plain, case)

    stamp = datetime.now(SINGAPORE).strftime("%Y-%m-%d-%H%M")
    model = status["model"].replace("/", "-")
    out = HERE / "results"
    out.mkdir(exist_ok=True)
    (out / f"{stamp}-{model}-rag.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    report(results, status, sweep_result, writes, out / f"{stamp}-{model}-rag.md")


if __name__ == "__main__":
    main()
