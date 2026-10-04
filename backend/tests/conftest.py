import os
import tempfile

# Tests run against SQLite with the models switched off, so they need no database server, weights or network.
_tmp = tempfile.mkdtemp(prefix="deskless-test-")
os.environ["DATABASE_URL"] = f"sqlite:///{_tmp}/test.db"
os.environ["MEDIA_DIR"] = f"{_tmp}/media"
os.environ["WHISPER_ENABLED"] = "false"
os.environ["OLLAMA_URL"] = ""
