from fastapi.testclient import TestClient

from ml.api.main import app

# No `with`, so the model isn't loaded: these only check the secret is enforced before any route runs.
client = TestClient(app)


def test_without_a_secret_configured_requests_are_not_checked(monkeypatch):
    monkeypatch.delenv("ML_SHARED_SECRET", raising=False)

    assert client.get("/health").status_code == 200


def test_a_configured_secret_is_required(monkeypatch):
    monkeypatch.setenv("ML_SHARED_SECRET", "s3cret")

    refused = client.get("/model-info")
    assert refused.status_code == 401
    assert refused.json()["code"] == "ML_SECRET_REQUIRED"
    assert client.get("/model-info", headers={"X-ML-Secret": "wrong"}).status_code == 401
    assert client.get("/health").status_code == 200
