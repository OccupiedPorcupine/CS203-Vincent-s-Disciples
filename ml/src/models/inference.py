from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
from catboost import CatBoostRegressor

ML_DIR = Path(__file__).resolve().parents[2]

PRODUCTION_ARTIFACT_DIR = (
    ML_DIR / "artifacts" / "production"
)

MODEL_PATH = (
    PRODUCTION_ARTIFACT_DIR / "demand_model.cbm"
)

FEATURE_SCHEMA_PATH = (
    PRODUCTION_ARTIFACT_DIR / "feature_schema.json"
)


# load schema
def load_feature_schema() -> dict:

    if not FEATURE_SCHEMA_PATH.exists():
        raise FileNotFoundError(
            f"Feature schema not found at {FEATURE_SCHEMA_PATH}"
        )

    with FEATURE_SCHEMA_PATH.open(
        "r",
        encoding="utf-8",
    ) as file:
        schema = json.load(file)

    return schema


#load trained model
def load_model() -> CatBoostRegressor:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(
            f"Production model not found at {MODEL_PATH}"
        )

    model = CatBoostRegressor()

    model.load_model(
        MODEL_PATH
    )

    return model


# validate prediction input
def validate_features(
    features: dict,
    schema: dict,
) -> None:
    expected_features = set(
        schema["feature_order"]
    )

    supplied_features = set(
        features.keys()
    )

    missing_features = (
        expected_features
        - supplied_features
    )

    extra_features = (
        supplied_features
        - expected_features
    )

    if missing_features:
        raise ValueError(
            "Missing required features: "
            f"{sorted(missing_features)}"
        )

    if extra_features:
        raise ValueError(
            "Unexpected features: "
            f"{sorted(extra_features)}"
        )


def predict_demand(
    features: dict,
    model: CatBoostRegressor,
    schema: dict,
) -> float:
    """
    Predict demand for one dish-day input row.
    """

    validate_features(
        features,
        schema,
    )

    feature_order = schema[
        "feature_order"
    ]

    input_row = pd.DataFrame(
        [
            {
                feature: features[feature]
                for feature in feature_order
            }
        ],
        columns=feature_order,
    )

    prediction = model.predict(
        input_row
    )[0]

    return float(prediction)