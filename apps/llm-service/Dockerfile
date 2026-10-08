FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# Sandboxed language runtimes. bwrap is opt-in because the deployment kernel
# must allow user namespaces; subprocess remains the portable local fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bubblewrap \
        default-jdk-headless \
        g++ \
        nodejs \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --upgrade pip && pip install -r requirements.txt

COPY app ./app

EXPOSE 8091 9093

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
