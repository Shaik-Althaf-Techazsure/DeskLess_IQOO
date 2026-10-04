# ---- base: runtime dependencies, speech model, application code ----
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/hf \
    MEDIA_DIR=/data/media

WORKDIR /app
RUN useradd --create-home --uid 10001 app \
    && mkdir -p /data/media /opt/hf \
    && chown -R app:app /data /opt/hf

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the speech model into the image so the app needs no network at run time.
ARG WHISPER_MODEL=small
ENV WHISPER_MODEL=${WHISPER_MODEL}
USER app
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('${WHISPER_MODEL}', device='cpu', compute_type='int8')"
ENV HF_HUB_OFFLINE=1

COPY --chown=app:app app ./app

# ---- test: docker build --target test -t deskless-test backend && docker run --rm deskless-test ----
FROM base AS test
USER root
COPY requirements-dev.txt .
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY --chown=app:app tests ./tests
USER app
CMD ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider"]

# ---- runtime (default target) ----
FROM base AS runtime
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
