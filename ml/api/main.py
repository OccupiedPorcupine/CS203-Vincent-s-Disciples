import hmac
import os
from contextlib import asynccontextmanager

import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from ml.api.schemas import (
    HealthResponse,
    ModelInfoResponse,
    PredictionRequest,
    PredictionResponse,
)
from ml.src.features.feature_builder import build_features
from ml.src.models.inference import (
    load_feature_schema,
    load_model,
    load_model_metadata,
    predict_demand,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load these once when the API starts.
    app.state.model = load_model()
    app.state.schema = load_feature_schema()
    app.state.metadata = load_model_metadata()

    yield


app = FastAPI(
    title="Demand Forecasting Service",
    lifespan=lifespan,
)


# When ML_SHARED_SECRET is set, only callers that send it (the backend, as X-ML-Secret) are
# served, apart from /health. For hosts where this service can't be kept off the internet;
# on Vercel only the backend can reach it anyway (see deploy/README.md).
@app.middleware("http")
async def require_shared_secret(request: Request, call_next):
    secret = os.environ.get("ML_SHARED_SECRET", "")
    if secret and request.url.path != "/health":
        sent = request.headers.get("x-ml-secret", "")
        if not hmac.compare_digest(sent.encode(), secret.encode()):
            return JSONResponse(
                status_code=401,
                content={"code": "ML_SECRET_REQUIRED", "message": "Missing or wrong X-ML-Secret header"},
            )
    return await call_next(request)


@app.get(
    "/health",
    response_model=HealthResponse,
)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
    )


# The backend uses this to avoid model forecasts for dishes the
# model never saw in training, which it can't forecast meaningfully.
@app.get(
    "/model-info",
    response_model=ModelInfoResponse,
)
def model_info() -> ModelInfoResponse:
    return ModelInfoResponse(
        dishes=app.state.metadata.get("dishes"),
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