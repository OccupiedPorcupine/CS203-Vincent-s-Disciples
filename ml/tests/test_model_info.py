import pytest
from fastapi.testclient import TestClient

from ml.src.models.inference import (
    FEATURE_SCHEMA_PATH,
    MODEL_PATH,
    load_model_metadata,
)


def test_model_info_lists_the_training_dishes():
    if not MODEL_PATH.exists() or not FEATURE_SCHEMA_PATH.exists():
        pytest.skip(
            "Production model artifacts are not available"
        )

    from ml.api.main import app

    with TestClient(app) as client:
        response = client.get("/model-info")

    assert response.status_code == 200

    expected = load_model_metadata().get("dishes")

    assert response.json() == {"dishes": expected}


def test_missing_metadata_means_unknown_dishes(monkeypatch, tmp_path):
    monkeypatch.setattr(
        "ml.src.models.inference.MODEL_METADATA_PATH",
        tmp_path / "missing.json",
    )

    assert load_model_metadata() == {}
