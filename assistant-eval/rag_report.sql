-- What stall documents (RAG, issues #150-#155) cost per message, from the run of run_rag_eval.py:
-- the same questions from eval-rag@chicky.test (with documents) and eval-rag-nodocs@chicky.test
-- (without). Read-only; works in PostgreSQL and H2 (see README.md for running it against a local
-- backend). Prices are app.assistant.prices.*: USD 0.10 per million input tokens, 0.01 cached,
-- 0.50 output, and 0.02 per million embedding tokens.

-- 1. Language-model tokens and cost per message, with and without documents.
SELECT u.email, COUNT(*) AS messages,
       ROUND(AVG(i.input_tokens)) AS avg_input_tokens,
       ROUND(AVG(i.cached_tokens)) AS avg_cached_tokens,
       ROUND(AVG(i.output_tokens)) AS avg_output_tokens,
       ROUND(AVG((i.input_tokens - i.cached_tokens) * 0.10 + i.cached_tokens * 0.01 + i.output_tokens * 0.50) / 1000000, 6) AS avg_usd_per_message,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY i.latency_ms) AS median_ms
FROM assistant_interactions i JOIN app_users u ON u.id = i.owner_id
WHERE u.email IN ('eval-rag@chicky.test', 'eval-rag-nodocs@chicky.test') AND i.input_tokens IS NOT NULL
GROUP BY u.email
ORDER BY u.email;

-- 2. Embedding tokens: indexing the documents and embedding each question.
SELECT u.email, SUM(d.embedding_tokens) AS embedding_tokens,
       ROUND(SUM(d.embedding_tokens) * 0.02 / 1000000, 6) AS embedding_usd
FROM assistant_daily_usage d JOIN app_users u ON u.id = d.owner_id
WHERE u.email IN ('eval-rag@chicky.test', 'eval-rag-nodocs@chicky.test')
GROUP BY u.email
ORDER BY u.email;

-- 3. How many messages searched again with search_documents (retrieval_json "via":"tool").
SELECT COUNT(*) AS messages_with_documents,
       SUM(CASE WHEN i.retrieval_json LIKE '%"via":"tool"%' THEN 1 ELSE 0 END) AS searched_again
FROM assistant_interactions i JOIN app_users u ON u.id = i.owner_id
WHERE u.email = 'eval-rag@chicky.test' AND i.retrieval_json IS NOT NULL;
