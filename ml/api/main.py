from contextlib import asynccontextmanager

import pandas as pd
from fastapi import FastAPI, HTTPException

from ml.api.schemas import (
    HealthResponse,
    PredictionRequest,
    PredictionResponse,
)
from ml.src.features.feature_builder import build_features
from ml.src.models.inference import (
    load_feature_schema,
    load_model,
    predict_demand,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load these once when the API starts.
    app.state.model = load_model()
    app.state.schema = load_feature_schema()

    yield


app = FastAPI(
    title="Demand Forecasting Service",
    lifespan=lifespan,
)


@app.get(
    "/health",
    response_model=HealthResponse,
)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
    )


@app.post(
    "/predict",
    response_model=PredictionResponse,
)
def predict(
    request: PredictionRequest,
) -> PredictionResponse:

    sales_history = pd.DataFrame(
        [
            {
                "DEMAND_DATE": record.date,
                "DEMAND": record.demand,
            }
            for record in request.sales_history
        ]
    )

    weather = {
        "WIND": request.weather.wind,
        "CLOUD_COVER": request.weather.cloud_cover,
        "PRECIPITATION": request.weather.precipitation,
        "SUNSHINE": request.weather.sunshine,
        "AIR_TEMPERATURE": request.weather.air_temperature,
    }

    try:
        features = build_features(
            dish=request.dish,
            forecast_date=pd.Timestamp(
                request.forecast_date
            ),
            sales_history=sales_history,
            weather=weather,
            is_holiday=request.is_holiday,
        )

        prediction = predict_demand(
            features,
            app.state.model,
            app.state.schema,
        )

    except ValueError as error:
        raise HTTPException(
            status_code=422,
            detail=str(error),
        ) from error

    return PredictionResponse(
        dish=request.dish,
        forecast_date=request.forecast_date,
        predicted_demand=prediction,
    )