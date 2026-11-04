# Prediction API image, used by the private Vercel "ml" service (see vercel.json).
# Build locally with: docker build -t chicky-ml ml

FROM python:3.13-slim
RUN useradd --system --create-home app
WORKDIR /home/app
COPY requirements-torch.txt requirements-api.txt ./
RUN pip install --no-cache-dir -r requirements-torch.txt --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements-api.txt
# Chronos-2's weights (#106), pinned to a revision, so the service never downloads them at startup.
ARG CHRONOS_REVISION=29ec3766d36d6f73f0696f85560a422f50e8498c
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('amazon/chronos-2', revision='${CHRONOS_REVISION}', local_dir='ml/artifacts/chronos-2', allow_patterns=['config.json', 'model.safetensors'])"
# The code imports itself as the "ml" package, so keep that folder name.
COPY api ml/api
COPY src ml/src
COPY artifacts/production ml/artifacts/production
USER app
ENV PORT=8080 HF_HUB_OFFLINE=1
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn ml.api.main:app --host 0.0.0.0 --port ${PORT}"]
