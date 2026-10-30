-- Closed-day changes the assistant proposed (JSON list of ProposedClosure), shown on the same
-- confirm card as any proposed sales and saved when the user confirms. Null when there were none.
ALTER TABLE assistant_interactions ADD COLUMN closures_json VARCHAR(4000);
