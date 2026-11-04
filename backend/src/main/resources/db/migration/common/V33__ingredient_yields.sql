-- Ingredient yields and perishability (issue #89). A recipe gives the cooked amount one plate uses;
-- yield_percent is cooked (or usable) weight ÷ purchased weight, so the amount to buy is the cooked
-- amount ÷ yield. It can be above 100 for food that gains weight, such as rice. perishable says
-- whether it is bought for each day or kept in stock. NULL in either means "not set by the owner":
-- the backend then uses its default for the ingredient's name (USDA cooking yields, see
-- IngredientDefaults), or 100% and perishable.
ALTER TABLE ingredients ADD COLUMN yield_percent NUMERIC(4, 1);
ALTER TABLE ingredients ADD CONSTRAINT ingredients_yield_percent_check CHECK (yield_percent > 0 AND yield_percent <= 500);
ALTER TABLE ingredients ADD COLUMN perishable BOOLEAN;
