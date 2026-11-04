-- How the assistant does in real use, from assistant_interactions (one row per message; see V8,
-- V16 and V27 in backend/src/main/resources/db/migration/common). Read-only. Works in PostgreSQL
-- (psql "$DATABASE_URL" -f assistant-eval/interactions_report.sql) and in H2 (see README.md).
--
-- To leave out test accounts, add e.g.
--   AND owner_id NOT IN (SELECT id FROM app_users WHERE email LIKE '%@chicky.test')
-- to each WHERE clause.

-- 1. What users did with proposals: confirmed as proposed, edited then confirmed, cancelled,
--    left to expire (15 minutes), or still pending. Rows from before V16 have no proposal_type
--    and are sales.
SELECT COALESCE(proposal_type, 'SALES') AS proposal_type, outcome, COUNT(*) AS proposals
FROM assistant_interactions
WHERE outcome <> 'NONE'
GROUP BY COALESCE(proposal_type, 'SALES'), outcome
ORDER BY 1, 2;

-- 2. The same by input mode and language (en, zh, mixed; empty when only numbers were sent).
SELECT input_mode, COALESCE(detected_languages, '') AS languages, outcome, COUNT(*) AS proposals
FROM assistant_interactions
WHERE outcome <> 'NONE'
GROUP BY input_mode, COALESCE(detected_languages, ''), outcome
ORDER BY 1, 2, 3;

-- 3. Server-side time per message: from receiving the text to the reply, including every model
--    and tool call (not transcription or the network to the phone). offline-rules is left out.
SELECT model, input_mode, COUNT(*) AS messages,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms) AS median_ms,
       PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY latency_ms) AS p90_ms,
       MAX(latency_ms) AS max_ms
FROM assistant_interactions
WHERE model <> 'offline-rules'
GROUP BY model, input_mode
ORDER BY 1, 2;

-- 4. Tokens and language-model cost per message, at the list prices in application.properties
--    (app.assistant.prices.*: USD 0.10 per million input tokens, 0.01 cached, 0.50 output).
--    Transcription and spoken replies are extra; GET /api/admin/assistant-usage estimates them.
--    Only rows from V27 on have token counts.
SELECT model, COUNT(*) AS messages,
       ROUND(AVG(input_tokens)) AS avg_input_tokens,
       ROUND(AVG(cached_tokens)) AS avg_cached_tokens,
       ROUND(AVG(output_tokens)) AS avg_output_tokens,
       ROUND(AVG((input_tokens - cached_tokens) * 0.10 + cached_tokens * 0.01 + output_tokens * 0.50) / 1000000, 6) AS avg_usd_per_message
FROM assistant_interactions
WHERE input_tokens IS NOT NULL AND model <> 'offline-rules'
GROUP BY model;
