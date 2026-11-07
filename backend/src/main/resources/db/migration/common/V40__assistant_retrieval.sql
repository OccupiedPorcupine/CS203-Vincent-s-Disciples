-- What document search found for each assistant message (issue #153): the chunks retrieved, with
-- their scores and whether they came with the message or from the search_documents tool, and the
-- documents the reply cited. Used to measure retrieval (#155) and to debug wrong answers. Null
-- for stalls without documents and for messages from before this migration.
ALTER TABLE assistant_interactions ADD COLUMN retrieval_json VARCHAR(8000);
