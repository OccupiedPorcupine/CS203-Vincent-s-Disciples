from datetime import date

from pydantic import BaseModel, Field


class SalesRecord(BaseModel):
    date: date
    demand: float = Field(ge=0)


class WeatherInput(BaseModel):
    wind: float
    cloud_cover: float
    precipitation: float
    sunshine: float
    air_temperature: float


class PredictionRequest(BaseModel):
    dish: str
    forecast_date: date
    sales_history: list[SalesRecord]
    weather: WeatherInput
    is_holiday: bool


class PredictionResponse(BaseModel):
    dish: str
    forecast_date: date
    predicted_demand: float


class HealthResponse(BaseModel):
    status: str