# ---- base: dependencies and application code ----
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DATA_DIR=/data

WORKDIR /app
RUN useradd --create-home --uid 10001 app \
    && mkdir -p /data \
    && chown -R app:app /data

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY --chown=app:app app ./app

# ---- test: docker build --target test -t deskless-server-test server && docker run --rm deskless-server-test ----
FROM base AS test
COPY requirements-dev.txt .
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY --chown=app:app tests ./tests
USER app
CMD ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider"]

# ---- runtime (default target) ----
FROM base AS runtime
USER app
EXPOSE 8000
# --proxy-headers: behind a web server that adds HTTPS, the app still sees who is really calling.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
