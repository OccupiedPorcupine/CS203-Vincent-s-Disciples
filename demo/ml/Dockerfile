FROM python:3.13-slim
WORKDIR /app
COPY ml/requirements.txt /app/ml/requirements.txt
RUN pip install --no-cache-dir -r /app/ml/requirements.txt
COPY ml /app/ml
COPY hawker_datasets /app/hawker_datasets
EXPOSE 8000
CMD ["uvicorn", "ml.service.main:app", "--host", "0.0.0.0", "--port", "8000"]
