-- What a proposal is about (issue #43): SALES (daily sales, as before) or PRICE (a dish's new
-- price). Null for messages without a proposal and for proposals made before this column.
ALTER TABLE assistant_interactions ADD COLUMN proposal_type VARCHAR(16);
