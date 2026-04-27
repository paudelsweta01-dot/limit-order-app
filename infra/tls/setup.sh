#!/usr/bin/env bash
# Plan §9.1 — generate self-signed TLS certs for `https://localhost`
# via mkcert. One-time setup per developer machine.
#
# What this does:
#   1. Verifies `mkcert` is installed.
#   2. Runs `mkcert -install` (idempotent) to add the local CA to the
#      system + browser trust stores. THIS USES sudo on Linux/macOS;
#      mkcert prompts for credentials.
#   3. Generates `localhost.pem` + `localhost-key.pem` for hosts
#      `localhost` and `127.0.0.1` into the script's directory.
#
# After this, run the stack with the TLS overlay:
#   docker compose -f docker-compose.yml -f infra/tls/docker-compose.tls.yml up
#
# Browser then opens https://localhost/ without warnings.
#
# These cert files are .gitignore'd; never commit them.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if ! command -v mkcert >/dev/null 2>&1; then
    cat <<EOF
mkcert is not installed. Install it first:

    macOS:    brew install mkcert nss
    Linux:    https://github.com/FiloSottile/mkcert#installation
    Windows:  choco install mkcert

Then re-run: make tls-setup
EOF
    exit 1
fi

echo "==> mkcert -install (may prompt for sudo to install the local CA)"
mkcert -install

echo "==> generating localhost certs in $PWD"
mkcert -cert-file localhost.pem -key-file localhost-key.pem localhost 127.0.0.1 ::1

echo "==> chmod 600 the key file"
chmod 600 localhost-key.pem

ls -l localhost.pem localhost-key.pem

cat <<EOF

Done. To start the stack with TLS:

    docker compose -f docker-compose.yml -f infra/tls/docker-compose.tls.yml up --build

then open: https://localhost/
EOF
