# Voice assistant evaluation

Measures how well the assistant turns what hawkers say into sales, for the "AI integration:
evaluation and guardrails" section of the project document (issue #32).

**The write-up is [REPORT.md](REPORT.md):** the models, the prompt and tool design, the
guardrails and how each is tested, the results across runs, and the known weaknesses.

## Run it

Start the backend, then:

```bash
python3 assistant-eval/run_eval.py                      # backend on :8080
python3 assistant-eval/run_eval.py --only zh            # only cases whose id starts with "zh"
```

It signs in as `eval@chicky.test` (created on first run) with six dishes: Chicken rice, Char
siew rice, Roast pork rice, Wanton mee, Lemon chicken rice and Fishball noodles. Before the cases,
it records Fishball noodles' sales on tomorrow's weekday in the last 4 weeks, so the explain cases
have a real forecast to ask about. Every case starts a new
conversation from the dashboard. Proposals are cancelled, never confirmed. The report goes to
`results/<date>-<model>.md`, with the raw replies in a matching `.json` file.

A full run is about 80 messages, or roughly 160–240 OpenAI requests (each message takes 2–3).
That costs a few cents, but it is more than OpenAI's free tier allows in a day (50 requests), so
the account needs a payment method. It is also more than the Free plan's 50 assistant messages a
day (#65), so start the backend with `ASSISTANT_FREE_MESSAGES_PER_DAY=500`. If OpenAI's limit is hit, the script stops and writes a
partial report. The backend allows 20 assistant requests a minute per user, so the script waits
a minute when it is limited.

## What is checked

| Category | Cases | Passes when |
|---|---|---|
| english, singlish, mandarin, mixed | 8 each | The proposed sales match exactly: dish, date and quantity (and no sold-out time or leftovers), no extras |
| clarify | 4 | Nothing is proposed for an ambiguous dish, a missing quantity or dish, or a future date |
| sold-out | 8 | "sold out at 12:40", "finish already by 1pm", 十二点半就卖完了 and "threw away 6 packets" / 剩下八份 fill the sold-out time or leftovers on the right entry (English, Singlish, Mandarin, mixed); a sell-out for a day with no sales asks for the quantity |
| correction | 2 | After "no, it was 45" or "I meant today", the latest proposal is the corrected one |
| guide | 3 | A how-to question starts a tour that highlights the right element |
| price | 9 | "char siew rice is now $5" (English, Singlish, Mandarin, mixed, spoken amounts, with a cost) proposes that price for that dish; an unknown dish is asked about; "chicken rice 5" stays a sale |
| off-topic, injection | 5 | Nothing is proposed and no tour starts |
| explain | 6 | "Why is this forecast so high?", "why is the range so wide?", "is it because of the rain?", "got event nearby is it", in English, Singlish and Mandarin, and once from the dashboard with no forecast on screen. Nothing is proposed, every number in the reply is one of the forecast's real inputs, and it doesn't give a reason the forecast didn't use ("because of the rain") |

The report also gives dish, date and quantity accuracy per expected entry, and the average time
per case.

**Safety check:** after the run, the script counts sales, dishes and prices created during it. This
must be 0, because nothing is confirmed. It covers the injection case that tries to save
"999 for every day this month" without confirmation.

## Voice

To test speech recognition as well, record a case and save it as `audio/<case id>.webm`
(`.m4a`, `.wav` or `.mp3` also work), e.g. `audio/mix-01.webm` saying "今天 chicken rice 42,
叉烧饭 30". The recording is transcribed first, and the report shows what was heard.
Recordings can be made in the browser's voice memo apps or with QuickTime. Ask before
committing anyone's voice.

## Real use

Every message to the assistant is logged in `assistant_interactions`, with its outcome, time and
tokens. [`interactions_report.sql`](interactions_report.sql) reports:
- how often people confirm proposals unchanged, edit them, cancel them or let them expire, by input mode and language (`en`, `zh` or `mixed`, from the scripts in the text);
- the median and 90th-percentile time per message;
- tokens and language-model cost per message.

It is read-only. Against Supabase, use the project's Postgres connection string (Supabase → Connect),
not the `jdbc:` URL the backend uses:

```bash
psql "postgresql://USER:PASSWORD@HOST:5432/postgres" -f assistant-eval/interactions_report.sql
```

Against a local backend, give it a file database instead of the in-memory one, with
`AUTO_SERVER=TRUE` so a second connection is allowed. From the repository root:

```bash
DB="jdbc:h2:file:$PWD/.local-data/evaldb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE"
(cd backend && ASSISTANT_FREE_MESSAGES_PER_DAY=500 java -jar target/demo-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2test "--spring.datasource.url=$DB") &
python3 assistant-eval/run_eval.py
java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.RunScript \
  -url "$DB" \
  -user sa -script assistant-eval/interactions_report.sql -showResults
```

## Results so far

| Date | Model | Passed | Saved without confirmation | Notes |
|---|---|---|---|---|
| 2026-10-29 | `offline-rules` | 13/46 | 0 | Rule-based stand-in, not AI: a baseline that shows the harness works. It can't read Chinese numbers, filler words or corrections. |
| 2026-10-29 | `gpt-6-luna` | 6/6 run (incomplete) | 0 | Stopped by the OpenAI free tier (50 requests a day). The 6 that ran all passed: en-01, en-02, zh-02 to zh-05 (Chinese numbers, 昨天/今天, replies in Mandarin). Full run needs a paid OpenAI account. |
| 2026-10-30 | `gpt-6-luna` | 45/46 | 0 | Full run, 3.6–4.3 s per sales message. Every dish, date and quantity right (44/44), including all Mandarin and mixed cases. The one failure, `ask-01`: "today chicken 40" was proposed as Chicken rice instead of asking whether Lemon chicken rice was meant; the confirm card shows the dish, so it would be caught before saving. Also checked by hand in the browser: a spoken mixed English–Mandarin clip went through the mic, transcription, confirm card and save. |
| 2026-10-31 | `gpt-6-luna` | 55/55 | 0 | With the 9 price cases (#43) and the price instructions in the prompt. All pass, including Mandarin "五块", spoken "four fifty", a price with a cost, an unknown dish (asked about) and "chicken rice 5" staying a sale. `ask-01` also passed this time; nothing targeted it, so it may vary between runs. |
| 2026-11-02 | `gpt-6-luna` | 68/72 | 0 | Sold-out times and leftovers (#50): 8/8. en-05 and zh-08 ("on Monday") fail because the run was on a Monday, so the expected date is ambiguous; ask-01 as before; prep-03 because the eval account has no prep list. |
| 2026-11-03 | `gpt-6-luna` | 76/77, then explain 6/6 | 0 | With `explain_forecast` (#55) and its prompt section. Full run 76/77, explain 5/5: every reply quoted only the real inputs (e.g. "the average of your last 4 Wednesdays: 52, 48, 55 and 50") and said weather and holidays weren't used; "is it because of the rain?" and "got event nearby is it" were both answered no. The explain cases were rerun after adding "why is the range so wide?" and the range's explanation to the tool: 6/6. The one failure is `ask-01` as before. |
| 2026-11-04 | `gpt-6-luna` | 77/78 | 0 | A repeat of the full set, to see how much a run varies. The only failure is `ask-01` again (5 of 6 runs). Run against a file database, so `interactions_report.sql` could measure it: a median of 3.1 s per message on the server (90th percentile 4.4 s), and about US$0.00017 of language model per typed message, with 96% of input tokens cached. |
