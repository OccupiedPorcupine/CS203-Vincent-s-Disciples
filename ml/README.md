# Restaurant demand model comparison

This experiment extends PR #2's seven-dish Ridge notebook with Random Forest and
XGBoost regression. It reads the committed `data/raw/restaurant.csv` directly,
recreates the notebook's English column names and dish-day table in memory, and
keeps the **same target and feature information**. There are 760 recorded dates,
seven dishes, 5,320 observations, and 45 numeric inputs plus dish identity.

## Run

Use Python 3.13 and `uv` from the repository root:

```bash
uv venv --python 3.13 ml/.venv
uv pip install -r ml/requirements.txt --python ml/.venv/bin/python
ml/.venv/bin/python -m pytest ml/tests -q
ml/.venv/bin/python -m ml.model_comparison
```

The run is deterministic with seed 42 and one model thread. It takes several
minutes. It writes `ml/artifacts/model_comparison/metrics.csv` (overall and
per-dish MAE, RMSE, WAPE and negative prediction counts), `predictions.csv`
(actuals and all five predictions by date/dish), `cv_results.csv` (every tree
configuration and fold score), and `run.json` (source hash, features, versions,
split boundaries, audit, chosen parameters and validation winner). Artifacts
are ignored by Git because they can be regenerated. The verified run's
`results/metrics.csv` and `results/cv_results.csv` are tracked for review.
`--output-dir` changes the destination; `--candidates` changes the search budget from the default 20 for
quick local exploration, but changes the defined experiment.

## Design

| Split | Recorded dates | Dish-day rows | Purpose |
| --- | ---: | ---: | --- |
| 4 Oct 2013–24 Mar 2015 | 532 | 3,724 | Fit models and tune trees within this period |
| 25 Mar–16 Jul 2015 | 114 | 798 | Compare models and select winner by overall MAE |
| 17 Jul–7 Nov 2015 | 114 | 798 | Final held-out report |

The split uses the notebook's integer boundaries at 70% and 85% of sorted
unique dates. All seven dishes from a date stay together. Ridge uses the
notebook's one-hot dish encoding, standardized numeric features, and
`alpha=1.0`. The tree models receive the same columns with unscaled numeric
values. For each tree model, 20 reproducibly sampled configurations are ranked
by mean MAE across three expanding folds entirely inside training: 322/70,
392/70, and 462/70 fit/score dates. The selected configuration is refitted on
the original 3,724 training rows. Ridge and both naïve baselines retain their
original settings. The validation winner is fixed before the test set is
scored. No model is retrained on validation data.

The `DEMAND_T1` baseline predicts the previous **recorded** demand; `DEMAND_T7`
predicts demand seven **recorded observations** earlier. WAPE is sum of
absolute errors divided by sum of actual demand, shown as a percentage.

## Interpretation and limitations

This evaluates one-step predictions given recent **actual** dish demand and
target-date weather already in the source data. It is not a multi-day forecast
from a single origin. Advance use would require weather forecasts available at
prediction time. The source has five missing calendar dates across three gaps,
so T7 is not always exactly seven calendar days earlier.

The script checks that lag T1–T7, cumulative demand T2–T7, HML T7, and counts
above/below the seven-row mean match prior recorded demand after their warm-up
period. The source's three same-weekday mean fields do not have a documented
upstream definition and need a separate provenance audit. All models use those
fields unchanged for parity with PR #2. Negative model predictions are counted
but not clipped, preserving an honest comparison on the original outputs.

## Results

The full run used the pinned versions above and source CSV SHA-256
`eb7d66f029043e000016015666410ab0b6921d268b20c290a01b1c8949673e8b`.
The best training-CV MAE was 4.5599 for Random Forest and 4.5835 for XGBoost.
The selected forest used 200 trees, depth 12, 0.5 feature fraction and minimum
leaf size 8. The selected XGBoost configuration used 500 trees, depth 2,
learning rate 0.1, minimum child weight 3, and 0.8 row/column sampling.

| Split | Model | MAE ↓ | RMSE ↓ | WAPE ↓ | Negative predictions |
| --- | --- | ---: | ---: | ---: | ---: |
| Validation | Naive T1 | 7.644 | 11.498 | 42.23% | 0 |
| Validation | Seasonal T7 | 6.395 | 9.546 | 35.33% | 0 |
| Validation | Ridge | 4.954 | 7.092 | 27.37% | 20 |
| Validation | **Random Forest** | **4.785** | **7.080** | **26.44%** | 0 |
| Validation | XGBoost | 4.996 | 7.312 | 27.60% | 0 |
| Test | Naive T1 | 7.050 | 10.270 | 39.38% | 0 |
| Test | Seasonal T7 | 6.149 | 8.763 | 34.35% | 0 |
| Test | Ridge | 5.175 | 7.294 | 28.91% | 9 |
| Test | **Random Forest** | **4.950** | **7.156** | **27.65%** | 0 |
| Test | XGBoost | 5.131 | 7.382 | 28.66% | 1 |

Random Forest was selected by validation MAE before test evaluation. Its MAE
is 3.4% lower than Ridge on validation and 4.4% lower on test. XGBoost does
not beat Ridge on validation, though it narrowly improves test MAE. The
per-dish pattern matters: the forest improves low-volume calamari and fish
relative to both Ridge and the seasonal baseline, while Ridge remains better
for lamb and steak on test.

| Dish | Validation Ridge | Validation RF | Validation XGB | Test Ridge | Test RF | Test XGB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Calamari | 2.415 | 1.872 | 1.864 | 2.437 | 1.674 | 1.822 |
| Fish | 2.638 | 1.832 | 1.982 | 2.485 | 1.906 | 2.003 |
| Prawns | 3.770 | 3.651 | 3.525 | 3.667 | 3.298 | 3.310 |
| Chicken | 7.111 | 6.999 | 7.680 | 7.334 | 7.303 | 7.817 |
| Kofta | 5.551 | 5.823 | 5.662 | 7.303 | 6.955 | 6.935 |
| Lamb | 7.646 | 7.894 | 8.750 | 7.168 | 7.221 | 7.661 |
| Steak | 5.547 | 5.425 | 5.507 | 5.833 | 6.291 | 6.366 |

The per-dish table shows MAE (units). The tracked `results/metrics.csv` has
MAE, RMSE and WAPE for **every** model and dish on both splits; see
`results/cv_results.csv` for all candidate configurations and fold scores.

# Prep simulator and backtest (#85)

There is no public dataset of hawker stall sales, so `ml/prep_backtest.py` simulates stalls whose true demand is known and scores **prep policies** on what matters to an owner: leftovers, sell-outs and profit. It doesn't stop at forecast error.

```bash
ml/.venv/bin/python -m ml.prep_backtest            # 24 stalls, Jan 2024 - Jun 2026, about 45 s
ml/.venv/bin/python -m ml.prep_backtest --stalls 8 --eval-days 60
```

It writes `simulated_sales.csv`, `backtest_days.csv`, `summary.csv` and `ingredients.csv` to `ml/artifacts/simulation/`, which Git ignores.

## What is simulated

- **Real inputs:**
  - Singapore public holidays for 2020–2027 (`data/raw/sg_public_holidays.csv`, from data.gov.sg, Ministry of Manpower).
  - Daily rainfall at NEA's Admiralty station for 2009–2017 (`data/raw/sg_daily_rainfall.csv`, from data.gov.sg, Open Data Licence). Each simulated year borrows one recorded year by day of the year.
- **Stalls:**
  - Chicken rice, noodle and nasi lemak menus, with prices, costs and recipes.
  - Residential stalls (busy at weekends and on holidays) and office stalls (busy on weekdays).
  - Closures: rest days, NEA cleaning, Chinese New Year and leave.
  - Day-to-day noise, a shock that hits every dish at a stall at once, and a yearly trend.
- **Sell-outs:** owners prepare from habit: their last 4 same weekdays, plus a personal margin. When demand beats what was prepared, the stall sells out at a time worked out from the stall's customer pattern. So recorded sales are what the stall *had*, and the true demand is kept separately.
- **Assumptions:** every number that isn't real data is in `src/simulation/assumptions.py`. None comes from real hawker sales.

## How the backtest works

- For the last 182 days, a forecaster sees each dish's recorded sales and sell-out times, never the demand. A policy turns its forecast into portions, and the day is scored against the true demand.
- The loop is closed: what a policy prepares decides what sells, and those sales are what the forecaster sees the next day, as in the app.
- `AppBaseline` reproduces `BaselineForecaster.java`, and the tests check it against the Java logic.
- `SoldOutAwareBaseline` scales up sold-out days by how much of the day was left (#50). It uses a generic hawker customer pattern and sell-out times rounded to 30 minutes, not the simulated stall's true pattern.
- `AppSoldOutBaseline` is what the app does (`SoldOutDemand.java`, with `app.forecast.count-sold-out-demand` on). It assumes customers come evenly through the trading hours, caps the uplift at 1.5 times, and keeps the range of the recorded sales.
- Two reference rows:
  - **Owner's own habit** is the stalls without the app.
  - **Best possible** knows each day's true average and prepares by the margin rule. The gap between it and 100% is luck that no forecast can remove.

## Results (seed 7, 24 stalls, 13,890 dish-days)

| Forecaster | Policy | Forecast WAPE | Left over (% of demand) | Days sold out | % of best possible profit |
| --- | --- | ---: | ---: | ---: | ---: |
| Reference | Best possible | 19.1% | 13.7% | 33.9% | **85.6%** |
| — | Owner's own habit (no app) | — | 25.4% | 23.2% | 80.2% |
| App baseline | Forecast only | 32.0% | 3.1% | 79.0% | 69.3% |
| App baseline | Forecast + 20% | 23.2% | 20.2% | 30.3% | **81.8%** |
| App baseline | Top of likely range (prep list default) | 23.1% | 21.0% | 26.4% | 81.6% |
| App baseline | Margin rule | 28.4% | 8.2% | 55.4% | 75.6% |
| Baseline + sell-out times | Forecast only | 25.7% | 15.8% | 40.1% | 81.0% |
| Baseline + sell-out times | Margin rule | 25.4% | 22.0% | 31.5% | 79.4% |
| App baseline + sell-out times | Forecast only | 24.3% | 13.4% | 44.0% | 81.4% |
| App baseline + sell-out times | Top of likely range (prep list default) | 24.1% | 32.4% | 16.4% | 77.8% |
| App baseline + sell-out times | Margin rule | 24.2% | 20.6% | 30.3% | 80.9% |

WAPE is against the true demand. It differs between policies because each policy changes the sales the forecaster sees.

What this suggests:

1. **Sell-outs feed back into the forecast.** Preparing only the forecast sells out on 79% of days. Each sell-out lowers the next forecast, and profit falls to 69%. Any policy aimed near the middle of the forecast needs sell-out times (#50). With them, "forecast only" recovers to 81%.
2. **The margin rule needs a steadier range than the app's.** The app measures its range from the last 12 days' errors, which is too noisy to aim at a percentile. In a run with 8 weeks of errors (`AppBaseline.BACKTEST_DAYS = 56`), the margin rule reaches 81.8%, level with the best policy. That's the work for #86.
3. **Selling out is sometimes right.** At chicken rice margins (64%), the best possible policy sells out on a third of days and still makes the most money.
4. **The app's sell-out rule forecasts best, but the prep list isn't ready for it.** The app's rule (`AppSoldOutBaseline`) has the lowest forecast error, 24.3%. That's lower than the customer-pattern rule, because its 1.5 cap stops early sell-outs from swinging the forecast. But the prep list prepares to the top of the likely range, which only works because the plain forecast runs low after sell-outs. With sell-outs counted, it over-prepares: 32% leftovers, and profit falls from 81.6% to 77.8%. So `app.forecast.count-sold-out-demand` stays off until the prep list aims lower (#86). With sell-outs counted, preparing just the forecast already gives 81.4% with 13% leftovers, against 21% today.
5. **The app is only slightly ahead of the owners' habit (81.8% vs 80.2%).** The headroom up to 85.6% is what better forecasts (#87) and real sell-out data (#50, #88) have to win.

These numbers measure our assumptions, not real stalls (#6). Use them to compare methods and catch mistakes. For claims about real demand, use pilot data (#88).

# Pretrained forecasting models (#87)

`ml/chronos_benchmark.py` compares two pretrained models from Amazon with the app's baseline. Neither model is trained on our data:
- **Chronos-2** (120M parameters, Apache-2.0);
- **Chronos-Bolt tiny** (9M).

```bash
pip install -r ml/requirements.txt -r ml/requirements-chronos.txt   # adds PyTorch, about 1 GB
python -m ml.chronos_benchmark                                       # about 40 minutes on a laptop CPU
python -m ml.chronos_benchmark --parts restaurant resources --models chronos-bolt-tiny   # a minute
```

How the models are fed:
- `src/simulation/chronos_forecaster.py` gives each model a dish's daily sales up to the day before the forecast, with closed days left empty, so the weekly pattern stays seven days long.
- **Chronos-2** also gets each day's rainfall and whether it's a public holiday, including for the day being forecast. Chronos-Bolt takes no extra inputs.
- Neither is fine-tuned. The app and CI don't need PyTorch; only this benchmark does.

## Stuttgart restaurant: one day ahead from real sales

The test set is the same 110 dates and 7 dishes as `ml/model_comparison.py` (21 July – 7 November 2015). The trained models were tuned on the 17 months before it. Their figures come from re-running `python -m ml.model_comparison` on the current split; the committed `results/metrics.csv` predates a loader change and covers 114 dates.

| Model | Trained on this restaurant? | MAE | WAPE | Quantile loss | Share inside the 80% range |
| --- | --- | ---: | ---: | ---: | ---: |
| Naive: previous day | — | 7.11 | 39.5% | — | — |
| App baseline (mean of last 4 same weekdays) | — | 5.25 | 29.1% | 2.21 | 65.3% |
| Ridge | yes | 5.22 | 29.0% | — | — |
| XGBoost | yes | 5.10 | 28.3% | — | — |
| Random Forest | yes | 5.01 | 27.8% | — | — |
| CatBoost | yes | 4.92 | 27.3% | — | — |
| **Chronos-2** | **no** | **4.89** | **27.1%** | **1.98** | 73.4% |
| Chronos-Bolt tiny | no | 5.14 | 28.5% | 2.07 | **78.7%** |

Chronos-2, without training, is as accurate as the best model trained and tuned on 17 months of this restaurant's sales. Chronos-Bolt tiny is level with XGBoost and Ridge, and its 80% range is the closest to honest. The app's range is too narrow: it contains the real number only 65% of the time.

## Simulator: what it does to prep (24 stalls, 13,890 dish-days)

This is the closed-loop backtest from #85: a policy's sell-outs change the sales the forecaster sees next.

| Forecaster | Best policy | Left over | Days sold out | % of best possible profit |
| --- | --- | ---: | ---: | ---: |
| Reference | Best possible: knows each day's true average | 13.7% | 33.9% | 85.6% |
| **Chronos-2 + sell-out times** | **Margin rule** | 18.1% | 28.9% | **83.6%** |
| Chronos-2 + sell-out times | Forecast only | 11.7% | 43.3% | 83.4% |
| Chronos-2 | Forecast + 20% | 21.7% | 23.9% | 83.1% |
| Chronos-2 | Margin rule | 8.5% | 50.4% | 82.4% |
| App baseline | Forecast + 20% | 20.2% | 30.3% | 81.8% |
| Baseline + sell-out times | Forecast only | 15.8% | 40.1% | 81.0% |
| Chronos-Bolt tiny | Forecast + 20% | 23.6% | 24.9% | 80.9% |
| — | Owner's own habit (no app) | 25.4% | 23.2% | 80.2% |

Every forecaster and policy pair is in `artifacts/chronos/simulator.csv` after a run. Against the true demand, Chronos-2's forecast error is 20.7%, the baseline's 23.1%, and the best possible 19.1%.

## Cost to run (Apple M-series CPU, 4 threads, each model in a fresh process)

| | Chronos-2 | Chronos-Bolt tiny |
| --- | ---: | ---: |
| Weights | 456 MB | 33 MB |
| Load time (weights on disk) | 3.3 s | 2.6 s |
| One stall's 5 dishes | 0.12 s | 0.01 s |
| 400 dishes in one batch | 7.3 s | 0.13 s |
| Peak memory | 1.6 GB | 0.86 GB |

Python, PyTorch and pandas take about 340 MB before either model loads.

## Recommendation: serve Chronos-2

1. **It is the most accurate forecaster we have, on real and simulated data.** Untrained, it matches the trained CatBoost on the restaurant. On the simulator it captures 3.4 of the 5.4 points between the owners' habits and the ceiling; the baseline captures 1.6.
2. **It works for any dish from the first weeks.** The served CatBoost only knows the Stuttgart restaurant's 7 dishes. So today every hawker dish falls back to the baseline (`DISH_NOT_IN_MODEL`), and #46 can't retrain it until real hawker data exists (#88).
3. **It makes the margin rule (#86) work.** Its ranges are good enough to aim at a percentile: the margin rule plus sell-out times (#50) is its best policy. The app's 12-day range isn't good enough (see #86).
4. **The cost to watch is memory.** Chronos-2 needs about 1.6 GB and a container image about 1 GB larger, with PyTorch and the weights. Measure its cold start and memory on Vercel before switching. If it doesn't fit, Chronos-Bolt tiny is the fallback: cheap and honest about its range, but no better than the baseline on the simulator.
5. **Keep the baseline as the fallback** for when the ML service is down, as it is today.

The restaurant result is one restaurant and 7 dishes, and the simulator measures our own assumptions (#6). Pilot data (#88) is still what will show the gain for real stalls.
