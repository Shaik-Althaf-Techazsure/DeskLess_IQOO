"""DeskLess API: site walk-throughs captured as photo + voice, structured locally, exported as a PDF report."""
import logging
import os
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from .config import get_settings
from .db import init_db
from .routers import inspections, system
from .services import speech

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    threading.Thread(target=speech.warm_up, daemon=True).start()  # load the speech model without delaying start-up
    yield


settings = get_settings()
os.makedirs(settings.media_dir, exist_ok=True)

app = FastAPI(title="DeskLess API", version="0.1.0", lifespan=lifespan, docs_url="/api/docs", openapi_url="/api/openapi.json")
app.include_router(system.router)
app.include_router(inspections.router)
app.mount("/api/media", StaticFiles(directory=settings.media_dir), name="media")
