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
