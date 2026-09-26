import pandas as pd
from fastapi.testclient import TestClient

from ml.api.main import app
from ml.model_comparison import load_dish_days


def test_predict_endpoint_returns_forecast():
    wide, long = load_dish_days()

    dish = "CHICKEN"
    forecast_date = pd.Timestamp("2014-12-27")

    # All historical sales before the forecast date.
    history = wide[
        wide["DEMAND_DATE"] < forecast_date
    ][
        [
            "DEMAND_DATE",
            dish,
        ]
    ]

    expected_row = long[
        (long["DEMAND_DATE"] == forecast_date)
        & (long["DISH"] == dish)
    ].iloc[0]

    payload = {
        "dish": dish,
        "forecast_date": str(
            forecast_date.date()
        ),
        "sales_history": [
            {
                "date": str(row["DEMAND_DATE"].date()),
                "demand": float(row[dish]),
            }
            for _, row in history.iterrows()
        ],
        "weather": {
            "wind": float(
                expected_row["WIND"]
            ),
            "cloud_cover": float(
                expected_row["CLOUD_COVER"]
            ),
            "precipitation": float(
                expected_row["PRECIPITATION"]
            ),
            "sunshine": float(
                expected_row["SUNSHINE"]
            ),
            "air_temperature": float(
                expected_row["AIR_TEMPERATURE"]
            ),
        },
        "is_holiday": bool(
            expected_row["ISHOLIDAY"]
        ),
    }

    with TestClient(app) as client:
        response = client.post(
            "/predict",
            json=payload,
        )

    assert response.status_code == 200

    body = response.json()

    assert body["dish"] == dish
    assert (
        body["forecast_date"]
        == "2014-12-27"
    )

    assert isinstance(
        body["predicted_demand"],
        float,
    )