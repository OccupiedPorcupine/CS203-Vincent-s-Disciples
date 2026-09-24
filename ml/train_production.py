from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from catboost import CatBoostRegressor

from ml.model_comparison import load_dish_days


ML_DIR = Path(__file__).resolve().parent

EXPERIMENT_ARTIFACT_DIR = (
    ML_DIR / "artifacts" / "model_comparison"
)

PRODUCTION_ARTIFACT_DIR = (
    ML_DIR / "artifacts" / "production"
)

RUN_METADATA_PATH = (
    EXPERIMENT_ARTIFACT_DIR / "run.json"
)

MODEL_PATH = (
    PRODUCTION_ARTIFACT_DIR / "demand_model.cbm"
)

FEATURE_SCHEMA_PATH = (
    PRODUCTION_ARTIFACT_DIR / "feature_schema.json"
)

MODEL_METADATA_PATH = (
    PRODUCTION_ARTIFACT_DIR / "model_metadata.json"
)


NON_FEATURE_COLUMNS = {
    "DEMAND_DATE",
    "DISH",
    "DEMAND",
}


def load_selected_catboost_params() -> dict:
    """
    Load the CatBoost hyperparameters selected during model comparison.

    These parameters were selected using training-only time-series
    cross-validation and validation performance. They are not retuned
    here.
    """

    if not RUN_METADATA_PATH.exists():
        raise FileNotFoundError(
            f"Could not find experiment metadata at "
            f"{RUN_METADATA_PATH}"
        )

    with RUN_METADATA_PATH.open("r", encoding="utf-8") as file:
        run_metadata = json.load(file)

    try:
        params = run_metadata[
            "selected_parameters"
        ][
            "CatBoost"
        ]
    except KeyError as error:
        raise KeyError(
            "run.json does not contain selected CatBoost parameters."
        ) from error

    return params


def main() -> None:
    _, long_df = load_dish_days()

    numeric_features = [
        column
        for column in long_df.columns
        if column not in NON_FEATURE_COLUMNS
    ]

    feature_columns = [
        "DISH",
        *numeric_features,
    ]

    X = long_df[feature_columns].copy()
    y = long_df["DEMAND"].copy()

    catboost_params = load_selected_catboost_params()

    model = CatBoostRegressor(
        random_seed=42,
        cat_features=["DISH"],
        loss_function="RMSE",
        verbose=False,
        allow_writing_files=False,
        **catboost_params,
    )

    model.fit(
        X,
        y,
    )

    PRODUCTION_ARTIFACT_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    model.save_model(
        MODEL_PATH,
        format="cbm",
    )

    feature_schema = {
        "categorical_features": [
            "DISH",
        ],
        "numeric_features": numeric_features,
        "feature_order": feature_columns,
        "target": "DEMAND",
    }

    with FEATURE_SCHEMA_PATH.open(
        "w",
        encoding="utf-8",
    ) as file:
        json.dump(
            feature_schema,
            file,
            indent=2,
        )

    model_metadata = {
        "model_type": "CatBoostRegressor",
        "trained_at_utc": datetime.now(
            timezone.utc
        ).isoformat(),
        "training_rows": len(long_df),
        "training_start_date": (
            long_df["DEMAND_DATE"]
            .min()
            .isoformat()
        ),
        "training_end_date": (
            long_df["DEMAND_DATE"]
            .max()
            .isoformat()
        ),
        "dishes": sorted(
            long_df["DISH"]
            .astype(str)
            .unique()
            .tolist()
        ),
        "num_numeric_features": len(
            numeric_features
        ),
        "num_categorical_features": 1,
        "hyperparameters": catboost_params,
        "random_seed": 42,
    }

    with MODEL_METADATA_PATH.open(
        "w",
        encoding="utf-8",
    ) as file:
        json.dump(
            model_metadata,
            file,
            indent=2,
        )

    print("Production model training complete.")
    print(
        f"Training rows: {len(long_df)}"
    )
    print(
        f"Numeric features: {len(numeric_features)}"
    )
    print(
        f"Model saved to: {MODEL_PATH}"
    )
    print(
        f"Feature schema saved to: "
        f"{FEATURE_SCHEMA_PATH}"
    )
    print(
        f"Metadata saved to: "
        f"{MODEL_METADATA_PATH}"
    )


if __name__ == "__main__":
    main()