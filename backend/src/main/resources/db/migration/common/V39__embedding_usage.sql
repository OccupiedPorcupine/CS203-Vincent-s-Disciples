-- Tokens embedded for document search each day (issue #152): documents when they are saved and
-- questions when they are asked, so the cost of stall documents shows next to the chat tokens.
ALTER TABLE assistant_daily_usage ADD COLUMN embedding_tokens BIGINT DEFAULT 0 NOT NULL;
