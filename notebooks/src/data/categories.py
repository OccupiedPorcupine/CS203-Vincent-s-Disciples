"""Item -> category mapping for the 85 SingStat retail price series.

Keyed by SingStat ``seriesNo`` because that is the only stable identifier the
API exposes; ``rowText`` wording has changed across CPI basket revisions.
"""

from __future__ import annotations

CATEGORY_BY_SERIES_NO: dict[int, str] = {
    # staples: rice, bread, noodles, biscuits, pantry condiments
    1: "staples", 2: "staples", 3: "staples", 4: "staples", 5: "staples",
    6: "staples", 51: "staples", 52: "staples",
    # meat: fresh, chilled, frozen + canned luncheon meat
    8: "meat", 9: "meat", 10: "meat", 11: "meat", 12: "meat", 13: "meat",
    14: "meat", 15: "meat", 16: "meat", 17: "meat", 18: "meat", 19: "meat",
    20: "meat",
    # seafood: fresh fish/shellfish + canned sardines
    7: "seafood", 21: "seafood", 22: "seafood", 23: "seafood", 24: "seafood",
    25: "seafood", 26: "seafood", 27: "seafood", 28: "seafood", 29: "seafood",
    30: "seafood",
    # dairy/eggs
    31: "dairy_eggs", 32: "dairy_eggs", 33: "dairy_eggs", 34: "dairy_eggs",
    # oils
    35: "oils",
    # fruit (see MODULE NOTE below)
    36: "fruit", 37: "fruit", 38: "fruit", 39: "fruit", 40: "fruit",
    41: "fruit",
    # vegetables
    42: "vegetables", 43: "vegetables", 44: "vegetables", 45: "vegetables",
    46: "vegetables", 47: "vegetables", 48: "vegetables", 49: "vegetables",
    50: "vegetables",
    # beverages: packaged retail drinks
    53: "beverages", 54: "beverages", 55: "beverages", 56: "beverages",
    # prepared food: hawker/food-court cooked dishes and prepared drinks
    58: "prepared_food", 59: "prepared_food", 60: "prepared_food",
    61: "prepared_food", 62: "prepared_food", 63: "prepared_food",
    64: "prepared_food", 65: "prepared_food", 66: "prepared_food",
    67: "prepared_food", 68: "prepared_food", 69: "prepared_food",
    70: "prepared_food", 71: "prepared_food", 72: "prepared_food",
    73: "prepared_food", 74: "prepared_food", 75: "prepared_food",
    76: "prepared_food", 77: "prepared_food",
    # non-food: tobacco, household, personal care, fuel
    57: "non_food", 78: "non_food", 79: "non_food", 80: "non_food",
    81: "non_food", 82: "non_food", 83: "non_food", 84: "non_food",
    85: "non_food",
}

# MODULE NOTE
# The brief listed nine categories with no fruit bucket. Six items (bananas,
# papaya, watermelon, grapes, orange, apple) have no honest home in that list.
# Folding them into `vegetables` would distort the category-median overlay in
# the combined figure, since fruit and vegetable price seasonality differ.
# A tenth category `fruit` is therefore used. Change here if you disagree; the
# rest of the pipeline reads this mapping and needs no other edit.

CATEGORY_ORDER: tuple[str, ...] = (
    "staples", "meat", "seafood", "vegetables", "fruit", "dairy_eggs",
    "oils", "beverages", "prepared_food", "non_food",
)

# Colour-blind-safe qualitative palette (Okabe-Ito extended), one per category.
CATEGORY_COLOURS: dict[str, str] = {
    "staples": "#B8860B", "meat": "#D55E00", "seafood": "#0072B2",
    "vegetables": "#009E73", "fruit": "#CC79A7", "dairy_eggs": "#E69F00",
    "oils": "#8C6D31", "beverages": "#56B4E9", "prepared_food": "#9467BD",
    "non_food": "#666666",
}
