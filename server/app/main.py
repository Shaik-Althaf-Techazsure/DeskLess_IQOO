"""
The DeskLess server: accounts (sign-up with a code by email, approval by the level above, sign-in with a
unique ID), the reports the phones send, the work that follows from them, a dashboard for each role, and
the home page that shows what all of this is.
"""

import gzip
import hashlib
import re
import time
from contextlib import asynccontextmanager
from functools import lru_cache
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse
from sqlalchemy import text

from . import autopilot, metrics, writer
from .config import settings
from .db import SessionLocal, init_db
from .mailer import Mailer
from .routers import admin, auth, people, signup, work, types

STATIC = Path(__file__).parent / "static"


class UnderItsPath:
    """
    On its real domain the server lives under a path, such as /competitions/IQOO, behind the site's own
    web server. Some set-ups pass that path on with each request and some cut it off first. Either way
    the routes below see the request as if the server sat at the root. Capitals in the path do not matter.
    """

    def __init__(self, app, base: str):
        self.app = app
        self.base = base

    async def __call__(self, scope, receive, send):
        if self.base and scope["type"] == "http":
            path = scope["path"]
            under = path.lower().startswith(self.base.lower()) and path[len(self.base):len(self.base) + 1] in ("", "/")
            if under and len(path) == len(self.base) and scope["method"] in ("GET", "HEAD"):
                # The bare address without its last stroke. The home page asks for its files beside
                # itself, so the browser has to stand inside the folder first.
                asked = scope.get("query_string", b"").decode("latin-1")
                await RedirectResponse(path + "/" + (f"?{asked}" if asked else ""))(scope, receive, send)
                return
            if under:
                scope = dict(scope, path=path[len(self.base):] or "/")
                # The same path as the browser sent it; it is rebuilt from the one above when needed.
                scope.pop("raw_path", None)
        await self.app(scope, receive, send)


class Counted:
    """Times every answer, so the administrator's live monitor can show how busy and how quick the server is."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        began = time.perf_counter()
        answer = {"status": 500}

        async def noting(message):
            if message["type"] == "http.response.start":
                answer["status"] = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, noting)
        finally:
            metrics.record(scope["path"], answer["status"], (time.perf_counter() - began) * 1000)


class Squeezed:
    """
    Sends words compressed to whoever can take them: the page, its script and style, and every answer of
    the API. They shrink to a quarter or less, which on a slow line is most of the waiting. Pictures and
    PDFs are compressed already and pass as they are.
    """

    _WORDS = ("application/json", "text/", "application/javascript")
    _WORTH_IT = 600

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or b"gzip" not in dict(scope["headers"]).get(b"accept-encoding", b""):
            await self.app(scope, receive, send)
            return
        held = None          # the start of an answer that may be compressed, kept until its body is known
        parts: list[bytes] = []

        async def squeezing(message):
            nonlocal held
            if message["type"] == "http.response.start":
                headers = dict(message["headers"])
                if headers.get(b"content-type", b"").decode("latin-1").startswith(self._WORDS) and b"content-encoding" not in headers:
                    held = message
                    return
                await send(message)
            elif message["type"] == "http.response.body" and held is not None:
                parts.append(message.get("body", b""))
                if message.get("more_body"):
                    return
                body = b"".join(parts)
                if len(body) >= self._WORTH_IT:
                    body = gzip.compress(body, compresslevel=6)
                    kept = [(name, value) for name, value in held["headers"] if name != b"content-length"]
                    held = dict(held, headers=[*kept, (b"content-encoding", b"gzip"), (b"vary", b"Accept-Encoding"), (b"content-length", str(len(body)).encode())])
                await send(held)
                await send({"type": "http.response.body", "body": body})
            else:
                await send(message)

        await self.app(scope, receive, squeezing)


# Each page has its style and script beside it: the dashboard, the home page and the sign-up page.
_PAGE_FILES = {
    "app.css": "text/css", "app.js": "text/javascript",
    "home.css": "text/css", "home.js": "text/javascript",
    "signup.css": "text/css", "signup.js": "text/javascript",
}

# What the home page shows: the film, the deck's slides, pictures of the app and of the dashboards.
SITE = STATIC / "site"
_SITE_KINDS = {".webp": "image/webp", ".jpg": "image/jpeg", ".png": "image/png", ".mp4": "video/mp4", ".pdf": "application/pdf"}


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    # Where a writing model is named in the settings, it works beside the server from here on.
    writer.start()
    # The assistant's own rounds: reminders, and stuck issues taken one level up.
    autopilot.start(app.state.mailer)
    yield


app = FastAPI(title="DeskLess", version="2.0", lifespan=lifespan, docs_url="/api/docs", redoc_url=None, openapi_url="/api/openapi.json")
app.state.mailer = Mailer()
# Counted sits inside UnderItsPath, so it sees each path as the routes do, without the site's prefix.
app.add_middleware(Squeezed)
app.add_middleware(Counted)
app.add_middleware(UnderItsPath, base=settings().base_path)
app.include_router(signup.router)
app.include_router(auth.router)
app.include_router(admin.router)
app.include_router(people.router)
app.include_router(work.router)
app.include_router(types.router)


@app.exception_handler(HTTPException)
async def say_why(_: Request, error: HTTPException) -> JSONResponse:
    """Every refusal carries one sentence the app can show as it is."""
    return JSONResponse({"detail": error.detail}, status_code=error.status_code, headers=error.headers)


@app.get("/api/health")
def health() -> dict:
    with SessionLocal() as db:
        db.execute(text("SELECT 1"))
    return {"status": "ok", "mail": bool(settings().smtp_host), "ai": writer.status()["on"]}


def _mark() -> str:
    """A short mark of the page's script and style, which changes whenever they do."""
    mark = hashlib.sha256()
    for name in _PAGE_FILES:
        mark.update((STATIC / name).read_bytes())
    return mark.hexdigest()[:12]


@lru_cache
def _site() -> tuple[frozenset, str]:
    """The names of the home page's pictures and film, and a mark that changes when any of them does."""
    mark = hashlib.sha256()
    names = sorted(file.name for file in SITE.iterdir() if file.suffix in _SITE_KINDS) if SITE.is_dir() else []
    for name in names:
        about = (SITE / name).stat()
        mark.update(f"{name}:{about.st_size}:{int(about.st_mtime)};".encode())
    return frozenset(names), mark.hexdigest()[:12]


_SITE_FILE = re.compile(r'static/site/([\w.-]+)(?=")')

_FOR_GOOD = "public, max-age=31536000, immutable"


def _page(name: str) -> HTMLResponse:
    """
    A page as the browser gets it. It asks for its style, its script, its pictures and the API beside
    itself, so it works under whatever path the server is given. The page itself is always asked for
    afresh and is small; it names everything else by a mark, so the browser keeps those for good and
    fetches them again only when the server has been updated.
    """
    page = (STATIC / name).read_text(encoding="utf-8")
    mark = _mark()
    for file in _PAGE_FILES:
        page = page.replace(f"static/{file}", f"static/{file}?v={mark}")
    page = _SITE_FILE.sub(lambda found: f"{found.group(0)}?v={_site()[1]}", page)
    # Only what a shared link shows needs the whole address: chat apps fetch that picture from outside.
    page = page.replace("{{public}}", settings().public_url)
    return HTMLResponse(page, headers={"Cache-Control": "no-cache"})


@app.get("/", include_in_schema=False)
def home_page() -> HTMLResponse:
    """The front door: what DeskLess is and how it works, with the film, the deck and the way in."""
    return _page("home.html")


@app.get("/dashboard", include_in_schema=False)
def dashboard_page() -> HTMLResponse:
    """The one page everybody signs in on."""
    return _page("index.html")


@app.get("/signup", include_in_schema=False)
def signup_page() -> HTMLResponse:
    """Signing up in a browser: the same form, code and wait as in the app."""
    return _page("signup.html")


@app.get("/static/{name}", include_in_schema=False)
def page_file(name: str, v: str = "") -> FileResponse:
    if name not in _PAGE_FILES:
        raise HTTPException(404, "Not found.")
    keep = _FOR_GOOD if v and v == _mark() else "no-cache"
    return FileResponse(STATIC / name, media_type=_PAGE_FILES[name], headers={"Cache-Control": keep})


@app.get("/static/site/{name}", include_in_schema=False)
def site_file(name: str, v: str = "") -> FileResponse:
    """A picture, the film or the deck. The film is sent in pieces when the browser asks for pieces."""
    names, mark = _site()
    if name not in names:
        raise HTTPException(404, "Not found.")
    keep = _FOR_GOOD if v and v == mark else "public, max-age=3600"
    return FileResponse(SITE / name, media_type=_SITE_KINDS[Path(name).suffix], headers={"Cache-Control": keep})


@app.get("/admin", include_in_schema=False)
def to_the_dashboard() -> RedirectResponse:
    """Where the administrator's page used to be leads to the dashboard."""
    return RedirectResponse(f"{settings().base_path}/dashboard")
