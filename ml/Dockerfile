# Prediction API image, used by the private Vercel "ml" service (see vercel.json).
# Build locally with: docker build -t chicky-ml ml

FROM python:3.13-slim
RUN useradd --system --create-home app
WORKDIR /home/app
COPY requirements-api.txt .
RUN pip install --no-cache-dir -r requirements-api.txt
# The code imports itself as the "ml" package, so keep that folder name.
COPY api ml/api
COPY src ml/src
COPY artifacts/production ml/artifacts/production
USER app
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn ml.api.main:app --host 0.0.0.0 --port ${PORT}"]
