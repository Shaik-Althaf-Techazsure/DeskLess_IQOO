#!/bin/sh
# Brings the DeskLess server on this machine up to date with GitHub: pull, rebuild, restart, check.
# Run it from anywhere:  sudo sh deploy/update.sh   (plain "sh" will do for a user who may use Docker)
# Accounts, reports and pictures are kept: they live in Docker volumes, and the database gains any
# new columns by itself when the server starts.
set -e

cd "$(dirname "$0")/.."
compose="docker compose -f docker-compose.server.yml"

if [ ! -f .env ]; then
    echo "There is no .env here. Copy .env.example to .env and fill in the server's part of it." >&2
    exit 1
fi

# The code is fetched as whoever owns this folder, also when the rest runs as root.
owner=$(stat -c %U .)
if [ "$(id -u)" = "0" ] && [ "$owner" != "root" ] && command -v sudo >/dev/null; then
    sudo -u "$owner" git pull --ff-only
else
    git pull --ff-only
fi
$compose up -d --build

# The server says so itself once it is ready to answer.
tries=0
until $compose exec -T server python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=3)" >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 30 ]; then
        echo "The server did not come up. Its last lines:" >&2
        $compose logs --tail 40 server >&2
        exit 1
    fi
    sleep 2
done

$compose ps
echo "DeskLess is up: $(grep -E '^PUBLIC_URL=' .env | cut -d= -f2-)/dashboard"
