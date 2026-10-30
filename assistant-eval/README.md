# Voice assistant evaluation

Measures how well the assistant turns what hawkers say into sales, for the "AI integration:
evaluation and guardrails" section of the project document (issue #32).

## Run it

Start the backend, then:

```bash
python3 assistant-eval/run_eval.py                      # backend on :8080
python3 assistant-eval/run_eval.py --only zh            # only cases whose id starts with "zh"
```

It signs in as `eval@chicky.test` (created on first run) with five dishes: Chicken rice, Char
siew rice, Roast pork rice, Wanton mee and Lemon chicken rice. Every case starts a new
conversation from the dashboard. Proposals are cancelled, never confirmed. The report goes to
`results/<date>-<model>.md`, with the raw replies in a matching `.json` file.

A full run is about 50 messages, or roughly 100–150 OpenAI requests (each message takes 2–3).
That costs a few cents, but it is more than OpenAI's free tier allows in a day (50 requests), so
the account needs a payment method. If OpenAI's limit is hit, the script stops and writes a
partial report. The backend allows 20 assistant requests a minute per user, so the script waits
a minute when it is limited.

## What is checked

| Category | Cases | Passes when |
|---|---|---|
| english, singlish, mandarin, mixed | 8 each | The proposed sales match exactly: dish, date and quantity, no extras |
| clarify | 4 | Nothing is proposed for an ambiguous dish, a missing quantity or dish, or a future date |
| correction | 2 | After "no, it was 45" or "I meant today", the latest proposal is the corrected one |
| guide | 3 | A how-to question starts a tour that highlights the right element |
| off-topic, injection | 5 | Nothing is proposed and no tour starts |

The report also gives dish, date and quantity accuracy per expected entry, and the average time
per case.

**Safety check:** after the run, the script counts sales and dishes created during it. This
must be 0, because nothing is confirmed. It covers the injection case that tries to save
"999 for every day this month" without confirmation.

## Voice

To test speech recognition as well, record a case and save it as `audio/<case id>.webm`
(`.m4a`, `.wav` or `.mp3` also work), e.g. `audio/mix-01.webm` saying "今天 chicken rice 42,
叉烧饭 30". The recording is transcribed first, and the report shows what was heard.
Recordings can be made in the browser's voice memo apps or with QuickTime. Ask before
committing anyone's voice.

## Real use

Every message to the assistant is logged in `assistant_interactions` (see
`backend/src/main/resources/db/migration/common/V8__assistant_interactions.sql`). How often
people accept proposals unchanged, edit or cancel them:

```sql
SELECT outcome, count(*) AS proposals
FROM assistant_interactions
WHERE proposal_json IS NOT NULL
GROUP BY outcome;
```

And by input type and language. Each message is labelled `en`, `zh` or `mixed` from the scripts in the
text that was sent (empty when it's only numbers):

```sql
SELECT input_mode, detected_languages, outcome, count(*)
FROM assistant_interactions
WHERE proposal_json IS NOT NULL
GROUP BY input_mode, detected_languages, outcome
ORDER BY input_mode, detected_languages;
```

## Results so far

| Date | Model | Passed | Saved without confirmation | Notes |
|---|---|---|---|---|
| 2026-10-29 | `offline-rules` | 13/46 | 0 | Rule-based stand-in, not AI: a baseline that shows the harness works. It can't read Chinese numbers, filler words or corrections. |
| 2026-10-29 | `gpt-6-luna` | 6/6 run (incomplete) | 0 | Stopped by the OpenAI free tier (50 requests a day). The 6 that ran all passed: en-01, en-02, zh-02 to zh-05 (Chinese numbers, 昨天/今天, replies in Mandarin). Full run needs a paid OpenAI account. |
| 2026-10-30 | `gpt-6-luna` | 45/46 | 0 | Full run, 3.6–4.3 s per sales message. Every dish, date and quantity right (44/44), including all Mandarin and mixed cases. The one failure, `ask-01`: "today chicken 40" was proposed as Chicken rice instead of asking whether Lemon chicken rice was meant; the confirm card shows the dish, so it would be caught before saving. Also checked by hand in the browser: a spoken mixed English–Mandarin clip went through the mic, transcription, confirm card and save. |
