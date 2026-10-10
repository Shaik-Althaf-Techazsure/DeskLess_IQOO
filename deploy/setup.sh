#!/usr/bin/env bash
# Puts DeskLess on a server that already runs a website behind nginx, under a path of that site.
#
#   git clone https://github.com/Shaik-Althaf-Techazsure/DeskLess_IQOO.git
#   cd DeskLess_IQOO
#   sudo bash deploy/setup.sh
#
# It asks for the address and the administrator's email, writes the settings (.env) with keys made
# on the spot, starts the server and its database in Docker, and adds one line to the site's nginx
# so that the path leads to the server. The website itself is not touched.
#
# Safe to run again: an existing .env is kept, and nginx is not given the line twice.
#   sudo bash deploy/setup.sh nginx     only the nginx part
#   sudo bash deploy/setup.sh ai        adds the open-source model that words the assistant's summaries
# To update after a push to GitHub:  sh deploy/update.sh
set -euo pipefail

cd "$(dirname "$0")/.."
DEFAULT_URL="https://www.shaikalthaf.in/competitions/iqoo"
SNIPPET="/etc/nginx/snippets/deskless.conf"
BACKUPS="/etc/nginx/deskless-backup"
compose="docker compose -f docker-compose.server.yml"

step() { printf '\n== %s\n' "$*"; }
stop() { printf '\nStopped: %s\n' "$*" >&2; exit 1; }
setting() { grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true; }

# Asks a question when somebody is at the keyboard; otherwise takes what the environment gave.
ask() {
    local question=$1 given=$2 answer=""
    if [ -n "$given" ]; then printf '%s' "$given"; return; fi
    [ -t 0 ] || return 0
    read -r -p "$question " answer
    printf '%s' "$answer"
}

write_settings() {
    if [ -f .env ]; then
        step "Settings: keeping the .env that is here"
        return
    fi
    step "Settings"
    command -v openssl >/dev/null || stop "openssl is needed to make the keys. Install it (apt install openssl) and run this again."
    local url email
    url=$(ask "Address people will reach it at [$DEFAULT_URL]:" "${PUBLIC_URL:-}")
    url=${url:-$DEFAULT_URL}
    url=${url%/}
    case "$url" in http://*|https://*) ;; *) stop "The address must begin with https:// (or http://)." ;; esac
    email=$(ask "The administrator's email address (signs in on the dashboard):" "${ADMIN_EMAIL:-}")
    case "$email" in *@*.*) ;; *) stop "An email address is needed for the administrator. Run again, or set ADMIN_EMAIL." ;; esac

    local smtp_host smtp_port="" smtp_user="" smtp_password="" mail_from=""
    smtp_host=$(ask "Mail server that sends the sign-up codes, e.g. smtp.gmail.com (empty to set it later):" "${SMTP_HOST:-}")
    if [ -n "$smtp_host" ]; then
        smtp_port=$(ask "  its port [587]:" "${SMTP_PORT:-}")
        smtp_user=$(ask "  the account it signs in with:" "${SMTP_USER:-}")
        smtp_password=${SMTP_PASSWORD:-}
        if [ -z "$smtp_password" ] && [ -t 0 ]; then
            read -r -s -p "  that account's password (not shown): " smtp_password
            echo
        fi
        mail_from=${MAIL_FROM:-DeskLess <${smtp_user:-no-reply@deskless.local}>}
        case "$smtp_user$smtp_password$mail_from" in
            *"'"*) stop "A mail setting holds a single quote, which this script cannot write down. Leave the mail server empty here and put the SMTP_* lines into .env by hand afterwards." ;;
        esac
    fi

    # The file holds the keys, so only its owner may read it.
    umask 077
    {
        echo "# DeskLess on this server. Made by deploy/setup.sh; .env.example explains every line."
        echo "PUBLIC_URL=$url"
        echo "ADMIN_EMAIL=$email"
        echo "ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)"
        echo "# Keep a copy of these two somewhere safe. A lost SECRET_KEY signs everyone out; a lost"
        echo "# AADHAAR_KEY makes the stored Aadhaar numbers unreadable for good."
        echo "SECRET_KEY=$(openssl rand -base64 48 | tr -d '\n')"
        echo "AADHAAR_KEY=$(openssl rand -base64 32 | tr -d '\n')"
        echo
        echo "# Email. Without a mail server nobody receives their sign-up code: it is only written to the"
        echo "# server's log ($compose logs server)."
        if [ -n "$smtp_host" ]; then
            echo "SMTP_HOST=$smtp_host"
            echo "SMTP_PORT=${smtp_port:-587}"
            echo "SMTP_SECURITY=${SMTP_SECURITY:-starttls}"
            echo "SMTP_USER='$smtp_user'"
            echo "SMTP_PASSWORD='$smtp_password'"
            echo "MAIL_FROM='$mail_from'"
        else
            echo "# SMTP_HOST=smtp.example.com"
            echo "# SMTP_PORT=587"
            echo "# SMTP_SECURITY=starttls"
            echo "# SMTP_USER="
            echo "# SMTP_PASSWORD="
            echo "# MAIL_FROM=DeskLess <no-reply@example.com>"
        fi
    } > .env
    umask 022
    # Run with sudo, the file would belong to root; it is given to the person who ran it, like the rest.
    [ -z "${SUDO_UID:-}" ] || chown "$SUDO_UID:${SUDO_GID:-$SUDO_UID}" .env
    NEW_SETTINGS=1
    echo "Written to $(pwd)/.env"
}

start_server() {
    step "The server and its database, in Docker"
    command -v docker >/dev/null || stop "Docker is not installed on this machine. Install it (curl -fsSL https://get.docker.com | sh) and run this again."
    docker compose version >/dev/null 2>&1 || stop "Docker is here, but 'docker compose' is not. Install the Docker Compose plugin and run this again."
    $compose up -d --build
    local tries=0
    until $compose exec -T server python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=3)" >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -ge 45 ]; then
            $compose logs --tail 40 server >&2
            stop "The server did not come up; its last lines are above."
        fi
        sleep 2
    done
    echo "The server answers on this machine at 127.0.0.1:$(setting SERVER_PORT | grep . || echo 8800)."
}

# Adds the path to the site's nginx: the lines go into a file of their own, and the site's HTTPS
# server block gets one "include" for it, as its first line, so no other rule of the site comes first.
# The site's files are copied aside first, and put back if nginx does not accept the result.
join_nginx() {
    step "The site's nginx"
    local url path host port
    url=$(setting PUBLIC_URL)
    [ -n "$url" ] || stop "There is no PUBLIC_URL in .env."
    host=$(printf '%s' "$url" | sed -E 's#^[a-z]+://([^/:]+).*#\1#')
    path=$(printf '%s' "$url" | sed -E 's#^[a-z]+://[^/]+##; s#/+$##')
    port=$(setting SERVER_PORT)
    port=${port:-8800}
    if [ -z "$path" ]; then
        echo "PUBLIC_URL has no path, so the server is meant to have a domain to itself."
        echo "Point that domain's nginx at http://127.0.0.1:$port (see deploy/nginx.conf for the lines)."
        return
    fi
    if ! command -v nginx >/dev/null; then
        echo "nginx was not found on this machine. Whatever serves $host has to pass $path on to"
        echo "http://127.0.0.1:$port ; deploy/nginx.conf shows what is needed."
        return
    fi
    [ "$(id -u)" = "0" ] || stop "Changing nginx needs root. Run:  sudo bash deploy/setup.sh nginx"
    command -v python3 >/dev/null || stop "python3 is needed to edit the nginx file safely. Install it, or add the lines of deploy/nginx.conf by hand."

    local pattern
    pattern=$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')
    case "$pattern" in *[!a-z0-9/._-]*) stop "The path in PUBLIC_URL ($path) has characters this script does not put into nginx. Add the lines of deploy/nginx.conf by hand." ;; esac

    # Every file nginx reads, as nginx itself lists them. Asked first, while nothing has been changed.
    local files
    files=$(nginx -T 2>/dev/null | sed -n 's/^# configuration file \(.*\):$/\1/p')
    [ -n "$files" ] || stop "nginx could not list its configuration (nginx -T). Fix what it reports and run again."

    # The lines themselves, with this server's path and port. Those from an earlier run are kept
    # until nginx has accepted the new ones.
    mkdir -p "$(dirname "$SNIPPET")" "$BACKUPS"
    local earlier="$BACKUPS/deskless.conf.earlier"
    rm -f "$earlier"
    [ ! -f "$SNIPPET" ] || cp -p "$SNIPPET" "$earlier"
    sed -E "s#\\^/competitions/iqoo\\(/\\|\\\$\\)#^${pattern}(/|\$)#; s#127\\.0\\.0\\.1:8800#127.0.0.1:${port}#" deploy/nginx.conf > "$SNIPPET"
    if ! grep -q "location ~\\* \\^${pattern}(/|\\\$)" "$SNIPPET"; then
        if [ -f "$earlier" ]; then cp -p "$earlier" "$SNIPPET"; else rm -f "$SNIPPET"; fi
        stop "Could not make the nginx lines for the path $path from deploy/nginx.conf."
    fi
    echo "Wrote $SNIPPET"

    local stamp changed
    stamp=$(date +%Y%m%d-%H%M%S)
    changed=$(HOST="$host" STAMP="$stamp" BACKUPS="$BACKUPS" python3 - $files <<'PY'
import os
import re
import shutil
import sys

host = os.environ["HOST"].lower()
bare = host[4:] if host.startswith("www.") else host
names = {bare, "www." + bare}
INCLUDE = "include snippets/deskless.conf;"


def blank(text):
    """The text with comments and quoted strings spaced out, so braces and words can be found by position."""
    out, i, n = list(text), 0, len(text)
    while i < n:
        c = text[i]
        if c == "#":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            for k in range(i, min(j + 1, n)):
                if text[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


for path in sys.argv[1:]:
    try:
        text = open(path, encoding="utf-8").read()
    except (OSError, UnicodeDecodeError):
        continue
    plain = blank(text)
    opened, blocks = [], []
    for at, c in enumerate(plain):
        if c == "{":
            opened.append(at)
        elif c == "}" and opened:
            start = opened.pop()
            if re.search(r"(^|[\s;{}])server\s*$", plain[:start]):
                blocks.append((start, at))
    places = []
    for start, end in blocks:
        body = plain[start + 1:end]
        listens_https = re.search(r"(^|[\s;{}])listen\s[^;]*\b443\b", body)
        named = re.search(r"(^|[\s;{}])server_name\s([^;]*);", body)
        if not listens_https or not named or not names & set(named.group(2).lower().split()):
            continue
        if "snippets/deskless.conf" in text[start:end]:
            print("has " + path)
            continue
        places.append(start + 1)
    if places:
        shutil.copy2(path, os.path.join(os.environ["BACKUPS"], path.strip("/").replace("/", "_") + "." + os.environ["STAMP"]))
        for place in sorted(places, reverse=True):
            text = text[:place] + "\n    " + INCLUDE + "  # DeskLess, see deploy/setup.sh" + text[place:]
        with open(path, "w", encoding="utf-8") as out:
            out.write(text)
        print("new " + path)
PY
)
    local added already
    added=$(printf '%s\n' "$changed" | sed -n 's/^new //p')
    already=$(printf '%s\n' "$changed" | sed -n 's/^has //p')

    if [ -z "$added" ] && [ -z "$already" ]; then
        echo "No nginx server block for https://$host was found, so nothing was changed."
        echo "Add this line inside that site's  server { ... }  for port 443, as its first line:"
        echo "    include snippets/deskless.conf;"
        echo "then:  sudo nginx -t && sudo systemctl reload nginx"
        return
    fi
    [ -z "$already" ] || echo "Already in place in: $already"
    [ -z "$added" ] || echo "Added one include line to: $added   (the file as it was: $BACKUPS/*.$stamp)"

    if ! nginx -t 2>/tmp/deskless-nginx-test; then
        cat /tmp/deskless-nginx-test >&2
        for file in $added; do
            cp -p "$BACKUPS/$(printf '%s' "${file#/}" | tr '/' '_').$stamp" "$file"
        done
        if [ -f "$earlier" ]; then cp -p "$earlier" "$SNIPPET"; else rm -f "$SNIPPET"; fi
        stop "nginx did not accept the change, so everything was put back as it was. Nothing was reloaded."
    fi
    rm -f "$earlier"
    systemctl reload nginx 2>/dev/null || nginx -s reload
    echo "nginx has taken it: $path now leads to the server."
}

check_from_outside() {
    step "From outside"
    local url answer
    url=$(setting PUBLIC_URL)
    command -v curl >/dev/null || { echo "curl is not here to check with. Open $url/dashboard in a browser."; return; }
    # nginx takes a moment to hand over to its new settings, so the question is put a few times.
    local tries=0
    while :; do
        if answer=$(curl -fsS --max-time 20 "$url/api/health" 2>&1) && printf '%s' "$answer" | grep -q '"status":"ok"'; then
            echo "$url/api/health says: $answer"
            printf '%s' "$answer" | grep -q '"mail":false' && echo "No mail server is set yet: put the SMTP_* lines in .env and run  sh deploy/update.sh" || true
            return
        fi
        tries=$((tries + 1))
        [ "$tries" -lt 8 ] || break
        sleep 2
    done
    echo "$url/api/health did not answer as the server does:"
    printf '  %s\n' "$answer" | head -n 3
    echo "Check that the path is passed on by the web server that answers for that address."
}

# Switches on the writing model: an open-source language model that runs in Docker on this machine
# and words the assistant's summaries. It takes memory the websites here may need, so it is only
# started when asked for by name, and not at all when the machine is short.
start_model() {
    step "The writing model"
    [ -f .env ] || stop "There is no .env here yet. Run  sudo bash deploy/setup.sh  first."
    local free tries=0
    free=$(awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo 2>/dev/null || true)
    free=${free:-0}
    echo "Free memory on this machine: ${free} MB. The model takes about 1000 MB while it works."
    if [ "$free" -lt 1500 ] && [ "${FORCE:-}" != "1" ]; then
        stop "That is too little to run it beside everything else here. Nothing was changed. (FORCE=1 starts it anyway.)"
    fi
    if grep -qE '^COMPOSE_PROFILES=' .env; then
        grep -qE '^COMPOSE_PROFILES=(.*,)?ai(,.*)?$' .env || sed -i -E 's/^(COMPOSE_PROFILES=.+)$/\1,ai/; s/^COMPOSE_PROFILES=$/COMPOSE_PROFILES=ai/' .env
    else
        printf '\n# The open-source model that words the assistant (docker-compose.server.yml). Remove the line to do without it.\nCOMPOSE_PROFILES=ai\n' >> .env
    fi
    $compose up -d --build
    echo "On its first start the model is downloaded (about 800 MB) and loaded. Waiting for it to answer..."
    until $compose exec -T ai curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -ge 180 ]; then
            $compose logs --tail 20 ai >&2
            stop "The model has not answered after 15 minutes; its last lines are above. The dashboards work without it."
        fi
        sleep 5
    done
    echo "The model answers. Within a minute the server starts on the open issues, a few seconds each;"
    echo "each issue's brief then names the model that wrote its summary."
    echo "To do without it again: remove COMPOSE_PROFILES=ai from .env, then"
    echo "  sudo $compose --profile ai rm -sf ai"
}

NEW_SETTINGS=0
case "${1:-all}" in
    ai)
        start_model
        ;;
    nginx)
        [ -f .env ] || stop "There is no .env here yet. Run  sudo bash deploy/setup.sh  first."
        join_nginx
        check_from_outside
        ;;
    all)
        write_settings
        start_server
        join_nginx
        check_from_outside
        step "Done"
        echo "Dashboards:     $(setting PUBLIC_URL)/dashboard"
        echo "Administrator:  $(setting ADMIN_EMAIL)"
        if [ "$NEW_SETTINGS" = "1" ]; then
            echo "Password:       $(setting ADMIN_PASSWORD)    (made just now; it is in .env, nowhere else)"
        else
            echo "Password:       the ADMIN_PASSWORD line in $(pwd)/.env"
        fi
        echo "The app:        build it for $(setting PUBLIC_URL), or type that address on its first screen"
        echo "To update:      sudo sh deploy/update.sh    (after each push to GitHub)"
        ;;
    *)
        stop "Use:  sudo bash deploy/setup.sh   or   sudo bash deploy/setup.sh nginx   or   sudo bash deploy/setup.sh ai"
        ;;
esac
