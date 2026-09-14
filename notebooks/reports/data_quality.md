# Data Quality Report — SingStat Average Retail Prices (Monthly)

Source table `M213761`, generated from `data/processed/retail_prices.parquet`.

| | |
|---|---|
| Items | 85 |
| Months | 139 (2015-01 → 2026-07) |
| Panel cells | 11,815 |
| Observed | 9,547 (80.8%) |
| Missing | 2,268 (19.2%) |
| Items with interior gaps | 0 |

> **The panel is ragged and has not been filled.** Missing months are
> absent from the API payload entirely (there are no `na`/`-` tokens);
> they are reindexed here into explicit `NaN` rows. Nothing is
> forward-filled or interpolated anywhere in this pipeline.

> **Basket break:** SingStat rebased to the 2024 CPI basket at
> **2024-01**. Per the API footnote, prices either side are
> *not* a pure price comparison — the brand/outlet sample changed. Moves
> at that boundary are flagged ⚠ in the tables below and should be
> excluded from any volatility estimate.

## 1. Per-item coverage

| Item | Cat | Unit | First | Last | Obs | Gaps | Miss % | Min | Max | Largest MoM | When |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| Aerated Soft Drinks | beverages | per 4 cans | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.50 | 3.77 | +7.2% | 2024-01 ⚠ |
| Beer | beverages | per 6 cans | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 14.59 | 17.54 | +8.3% | 2024-10 |
| Instant Coffee | beverages | per 200 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 9.60 | 12.76 | +7.1% | 2025-02 |
| Non-Aerated Soft Drinks | beverages | per 6 packets | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.92 | 3.25 | +22.9% | 2019-03 |
| Cheese | dairy_eggs | per 12 slices | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 4.31 | 7.63 | +22.3% | 2019-01 |
| Fresh Milk | dairy_eggs | per litre | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.74 | 3.79 | +11.5% | 2019-01 |
| Hen Eggs | dairy_eggs | per 10 | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.15 | 3.41 | +9.4% | 2022-04 |
| Infant Milk Powder | dairy_eggs | per 100 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.82 | 7.44 | +4.0% | 2024-01 ⚠ |
| Apple | fruit | each | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 0.41 | 0.61 | +13.0% | 2025-05 |
| Bananas | fruit | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.93 | 2.90 | -7.3% | 2020-05 |
| Grapes | fruit | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 9.00 | 20.73 | +32.2% | 2021-08 |
| Orange | fruit | each | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 0.36 | 0.76 | +31.7% | 2019-01 |
| Papaya | fruit | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.78 | 3.27 | +17.5% | 2024-08 |
| Watermelon | fruit | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.28 | 2.05 | +24.1% | 2020-06 |
| Beef Cube, Frozen | meat | per 500 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.66 | 7.60 | -11.3% | 2019-03 |
| Canned Luncheon Meat | meat | per 100 gram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 1.22 | 1.33 | +7.3% | 2026-04 |
| Chicken Wing, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 7.74 | 11.72 | -14.3% | 2024-01 ⚠ |
| Chicken Wing, Frozen | meat | per 2 kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 7.85 | 10.56 | +14.0% | 2026-03 |
| Lean Pork, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 12.88 | 17.30 | +10.0% | 2022-06 |
| Lean Pork, Frozen | meat | per 500 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 3.91 | 5.50 | +9.9% | 2020-03 |
| Mutton, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 17.79 | 28.49 | -14.7% | 2024-01 ⚠ |
| Pork Rib Bones, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 15.99 | 21.03 | -10.4% | 2020-10 |
| Pork Rib Bones, Frozen | meat | per 500 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 3.71 | 6.49 | +34.5% | 2019-12 |
| Ribeye Beef, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 38.11 | 60.65 | +17.1% | 2026-04 |
| Streaky Pork, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 14.64 | 20.89 | +6.9% | 2020-01 |
| Whole Chicken, Chilled | meat | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.94 | 9.36 | +16.2% | 2022-06 |
| Whole Chicken, Frozen | meat | each | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.92 | 5.76 | +31.2% | 2017-12 |
| Baby Diapers | non_food | per 60 pieces per pack | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 20.83 | 23.18 | +4.4% | 2025-11 |
| Cigarettes | non_food | per pack | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 11.99 | 17.65 | +10.0% | 2026-03 |
| Diesel | non_food | per litre | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 0.94 | 3.94 | +34.9% | 2026-04 |
| Laundry Liquid Detergent | non_food | per kilogram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 3.40 | 3.79 | +5.9% | 2025-02 |
| Liquefied Petroleum Gas (LPG) | non_food | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.28 | 3.04 | +4.5% | 2026-04 |
| Petrol, 92 Octane | non_food | per litre | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.63 | 3.11 | +17.1% | 2026-03 |
| Petrol, 95 Octane | non_food | per litre | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.67 | 3.16 | +16.7% | 2026-03 |
| Petrol, 98 Octane | non_food | per litre | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.85 | 3.67 | +14.5% | 2026-03 |
| Toilet Paper | non_food | per 10 rolls | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 5.28 | 6.05 | +11.0% | 2025-10 |
| Cooking Oil | oils | per 2 kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.64 | 7.55 | +17.7% | 2020-04 |
| Canned Drink With Ice | prepared_food | per can | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 1.73 | 1.81 | +1.1% | 2026-06 |
| Char Kway Teow | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 4.53 | 4.81 | +1.5% | 2026-06 |
| Char Siew Rice | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 4.01 | 4.24 | +0.7% | 2024-10 |
| Chicken Chop | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 7.65 | 7.91 | +0.5% | 2024-10 |
| Chicken Nasi Briyani | prepared_food | per plate | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.16 | 7.07 | +3.2% | 2024-01 ⚠ |
| Chicken Rice | prepared_food | per plate | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 3.19 | 4.21 | -2.6% | 2024-01 ⚠ |
| Coffee/Tea With Condensed Milk | prepared_food | per cup | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.09 | 1.44 | +2.3% | 2023-02 |
| Coffee/Tea Without Milk | prepared_food | per cup | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 0.98 | 1.26 | +9.9% | 2025-09 |
| Duck Rice | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 4.51 | 4.80 | +0.9% | 2024-10 |
| Economical Rice (1 Meat & 2 Vegetables) | prepared_food | per plate | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 3.12 | 3.88 | -5.4% | 2024-01 ⚠ |
| Fishball Noodles | prepared_food | per bowl | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 3.18 | 4.41 | +2.0% | 2023-02 |
| Fried Carrot Cake | prepared_food | per plate | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.82 | 3.92 | +2.8% | 2024-01 ⚠ |
| Ice Kachang | prepared_food | per bowl | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.89 | 2.79 | +2.3% | 2022-02 |
| Mee Rebus | prepared_food | per bowl | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.99 | 4.15 | +1.9% | 2019-07 |
| Mee Siam | prepared_food | per bowl | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 3.77 | 4.00 | +0.8% | 2024-10 |
| Milo With Condensed Milk | prepared_food | per cup | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 1.56 | 1.64 | +0.6% | 2024-03 |
| Roti Prata (Plain) | prepared_food | per piece | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 0.99 | 1.29 | +3.5% | 2022-03 |
| Saba Fish With Rice | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 6.05 | 6.62 | +1.7% | 2026-05 |
| Sliced Fish Bee Hoon | prepared_food | per bowl | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 5.66 | 5.90 | +0.5% | 2025-06 |
| Wanton Noodles | prepared_food | per plate | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 4.37 | 4.58 | +1.1% | 2024-10 |
| Canned Sardines | seafood | per 100 gram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 0.81 | 0.91 | +11.0% | 2026-05 |
| Cod Fish | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 49.39 | 76.06 | +7.3% | 2025-04 |
| Flowery Grouper | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 12.55 | 20.91 | +34.0% | 2024-01 ⚠ |
| Golden Snapper | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 17.11 | 22.44 | +21.6% | 2022-01 |
| Medium Prawns | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 15.22 | 24.29 | +28.7% | 2026-02 |
| Salmon | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 26.61 | 39.82 | +8.9% | 2023-03 |
| Sea Bass | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 10.37 | 13.74 | -15.4% | 2018-03 |
| Spanish Mackerel (Batang) | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 10.49 | 14.71 | -14.3% | 2020-05 |
| Squids | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 12.31 | 21.93 | +14.6% | 2022-09 |
| Threadfin (Kurau) | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 46.67 | 66.29 | +18.7% | 2025-01 |
| White Pomfret | seafood | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 18.95 | 36.57 | +24.0% | 2017-01 |
| Canned Baked Beans | staples | per 100 gram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 0.40 | 0.46 | +10.0% | 2024-10 |
| Cracker | staples | per 100 gram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 0.96 | 1.05 | -7.7% | 2024-06 |
| Instant Noodles | staples | per 5 packets | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.12 | 4.07 | +37.9% | 2024-01 ⚠ |
| Light Soy Sauce | staples | per 640 ml | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 2.22 | 2.77 | +19.8% | 2024-09 |
| Premium Thai Rice | staples | per 5 kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 12.38 | 14.93 | +5.7% | 2020-02 |
| Sausage Bun | staples | each | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 1.60 | 1.71 | +3.0% | 2024-10 |
| White Sugar | staples | per 2 kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.92 | 3.66 | -8.0% | 2015-07 |
| Wholemeal Bread | staples | per 400 gram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.92 | 2.86 | +10.5% | 2024-01 ⚠ |
| Broccoli | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 5.18 | 7.43 | +16.8% | 2017-11 |
| Cabbage | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.03 | 3.00 | +13.6% | 2021-02 |
| Carrots | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.92 | 2.33 | +7.2% | 2022-07 |
| Chinese Kale (Kailan) | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 4.83 | 6.17 | +12.0% | 2016-02 |
| Old Ginger | vegetables | per kilogram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 5.67 | 7.67 | +21.5% | 2026-01 |
| Potatoes | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.94 | 3.20 | +7.0% | 2025-09 |
| Small Mustard (Chye Sim) | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 2.96 | 5.10 | -14.4% | 2018-02 |
| Small Onions | vegetables | per kilogram | 2024-01 | 2026-07 | 31 | 0 | 77.7 | 5.59 | 6.60 | -9.6% | 2024-12 |
| Tomatoes | vegetables | per kilogram | 2015-01 | 2026-07 | 139 | 0 | 0.0 | 1.78 | 3.52 | -27.3% | 2015-04 |

## 2. Summary

### Q1. How many items have continuous coverage over the last 10 years?

**64 of 85 items** are continuous across the trailing 10-year window (2016-07 → 2026-07, 121 months), with zero missing months.

The remaining **21** have at least one missing month in that window:

| Item | Category | Observed | Missing |
|---|---|---:|---:|
| Canned Luncheon Meat | meat | 31/121 | 90 |
| Saba Fish With Rice | prepared_food | 31/121 | 90 |
| Old Ginger | vegetables | 31/121 | 90 |
| Sausage Bun | staples | 31/121 | 90 |
| Light Soy Sauce | staples | 31/121 | 90 |
| Cracker | staples | 31/121 | 90 |
| Canned Baked Beans | staples | 31/121 | 90 |
| Canned Sardines | seafood | 31/121 | 90 |
| Wanton Noodles | prepared_food | 31/121 | 90 |
| Sliced Fish Bee Hoon | prepared_food | 31/121 | 90 |
| Milo With Condensed Milk | prepared_food | 31/121 | 90 |
| Baby Diapers | non_food | 31/121 | 90 |
| Mee Siam | prepared_food | 31/121 | 90 |
| Duck Rice | prepared_food | 31/121 | 90 |
| Chicken Chop | prepared_food | 31/121 | 90 |
| Char Siew Rice | prepared_food | 31/121 | 90 |
| Char Kway Teow | prepared_food | 31/121 | 90 |
| Canned Drink With Ice | prepared_food | 31/121 | 90 |
| Toilet Paper | non_food | 31/121 | 90 |
| Laundry Liquid Detergent | non_food | 31/121 | 90 |
| Small Onions | vegetables | 31/121 | 90 |

### Q2. Which items are too sparse to model?

**21 items** have fewer than 60 observations (~5 years of monthly data) and cannot support a seasonal model — they lack the repeated annual cycles needed to identify seasonality:

| Item | Category | Obs | First | Last | Why |
|---|---|---:|---|---|---|
| Canned Luncheon Meat | meat | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Baby Diapers | non_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Laundry Liquid Detergent | non_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Toilet Paper | non_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Canned Drink With Ice | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Char Kway Teow | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Char Siew Rice | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Chicken Chop | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Duck Rice | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Mee Siam | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Milo With Condensed Milk | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Saba Fish With Rice | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Sliced Fish Bee Hoon | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Wanton Noodles | prepared_food | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Canned Sardines | seafood | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Canned Baked Beans | staples | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Cracker | staples | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Light Soy Sauce | staples | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Sausage Bun | staples | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Old Ginger | vegetables | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |
| Small Onions | vegetables | 31 | 2024-01 | 2026-07 | starts at 2024 basket rebase |

### Q3. The 20 largest month-on-month moves in the panel

| # | Date | Item | Category | From | To | Move |
|---:|---|---|---|---:|---:|---:|
| 1 | 2024-01 ⚠ | Instant Noodles | staples | 2.80 | 3.86 | +37.9% |
| 2 | 2026-04 | Diesel | non_food | 2.92 | 3.94 | +34.9% |
| 3 | 2019-12 | Pork Rib Bones, Frozen | meat | 3.71 | 4.99 | +34.5% |
| 4 | 2024-01 ⚠ | Flowery Grouper | seafood | 15.60 | 20.91 | +34.0% |
| 5 | 2021-08 | Grapes | fruit | 11.37 | 15.03 | +32.2% |
| 6 | 2019-01 | Orange | fruit | 0.41 | 0.54 | +31.7% |
| 7 | 2017-12 | Whole Chicken, Frozen | meat | 3.37 | 4.42 | +31.2% |
| 8 | 2026-03 | Pork Rib Bones, Frozen | meat | 4.98 | 6.49 | +30.3% |
| 9 | 2026-03 | Diesel | non_food | 2.25 | 2.92 | +29.8% |
| 10 | 2026-02 | Medium Prawns | seafood | 17.83 | 22.95 | +28.7% |
| 11 | 2023-11 | Whole Chicken, Frozen | meat | 3.89 | 4.99 | +28.3% |
| 12 | 2018-07 | Whole Chicken, Frozen | meat | 3.09 | 3.96 | +28.2% |
| 13 | 2015-04 | Tomatoes | vegetables | 2.45 | 1.78 | -27.3% |
| 14 | 2019-06 | Pork Rib Bones, Frozen | meat | 3.73 | 4.75 | +27.3% |
| 15 | 2024-06 | Tomatoes | vegetables | 2.31 | 2.91 | +26.0% |
| 16 | 2019-05 | Whole Chicken, Frozen | meat | 4.16 | 3.09 | -25.7% |
| 17 | 2025-01 | Tomatoes | vegetables | 2.77 | 3.48 | +25.6% |
| 18 | 2018-12 | Pork Rib Bones, Frozen | meat | 3.99 | 5.00 | +25.3% |
| 19 | 2019-09 | Pork Rib Bones, Frozen | meat | 3.85 | 4.82 | +25.2% |
| 20 | 2019-11 | Pork Rib Bones, Frozen | meat | 4.93 | 3.71 | -24.7% |

⚠ **2 of the top 20 land exactly on 2024-01**, the CPI basket rebase. These are measurement artefacts from a changed brand/outlet sample, not market price moves. Treat them as a level shift and exclude them when fitting.

## 3. By category

| Category | Items | Observed | Missing % | Median MoM |σ| |
|---|---:|---:|---:|---:|
| staples | 8 | 680 | 38.8 | 2.81% |
| meat | 13 | 1,699 | 6.0 | 4.42% |
| seafood | 11 | 1,421 | 7.1 | 5.12% |
| vegetables | 9 | 1,035 | 17.3 | 4.69% |
| fruit | 6 | 834 | 0.0 | 4.98% |
| dairy_eggs | 4 | 556 | 0.0 | 2.30% |
| oils | 1 | 139 | 0.0 | 3.41% |
| beverages | 4 | 556 | 0.0 | 3.03% |
| prepared_food | 20 | 1,700 | 38.8 | 0.56% |
| non_food | 9 | 927 | 25.9 | 2.99% |

---
_Generated by `src/reporting/quality.py`. Regenerate: `python -m src.reporting.quality`._