ALTER TABLE ingredients ADD COLUMN stock_quantity NUMERIC(12, 3);
ALTER TABLE ingredients ADD COLUMN stock_counted_on DATE;
ALTER TABLE ingredients ADD COLUMN stock_expires_on DATE;
ALTER TABLE ingredients ADD CONSTRAINT ingredients_stock_quantity_check
    CHECK (stock_quantity >= 0);