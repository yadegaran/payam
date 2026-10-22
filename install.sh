#!/usr/bin/env bash
# Payam server installer — one command on an Ubuntu/Debian server (fresh or already hosting other sites):
#
#   curl -fsSL https://raw.githubusercontent.com/yadegaran/payam/main/install.sh | sudo bash -s -- --domain chat.example.com
#
# Re-run the same command to update. Remove with --uninstall (add --purge to also delete accounts/data).
#
# Isolation guarantees (safe next to other services):
#   - own Node.js in /opt/payam/node (system node/npm untouched), own system user "payam"
#   - listens on 127.0.0.1 on a free port only; never touches other nginx sites or certificates
#   - memory capped by systemd so it can't starve neighbours; nothing written to disk but public keys/profiles
set -euo pipefail

REPO="${PAYAM_REPO:-yadegaran/payam}"
REF="${PAYAM_REF:-main}"
NODE_VERSION="v24.16.0"
PREFIX=/opt/payam
DATA=/var/lib/payam
WEB=/var/www/payam
ACME=/var/www/payam-acme
UNIT=/etc/systemd/system/payam.service

DOMAIN="" EMAIL="" PORT="" MAX_RAM="" NO_TLS=0 UNINSTALL=0 PURGE=0

say()  { printf '\033[1;32m▶\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<EOF
Usage: install.sh --domain chat.example.com [options]
  --domain D     domain whose DNS A/AAAA record points to this server (required)
  --email E      e-mail for Let's Encrypt expiry notices (optional)
  --port P       local port for the service (default: first free from 8095)
  --max-ram MB   RAM for queued ciphertext + files (default: 1/4 of RAM, max 1024)
  --no-tls       HTTP only (testing, or you terminate TLS elsewhere) — the release app requires HTTPS
  --uninstall    remove service, nginx site and program files (keeps data)
  --purge        with --uninstall: also delete accounts/data and the certificate
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --max-ram) MAX_RAM="${2:-}"; shift 2 ;;
    --no-tls) NO_TLS=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --purge) PURGE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown option: $1" ;;
  esac
done

[ "$(id -u)" = 0 ] || die "run as root (sudo)"
[ -d /run/systemd/system ] || die "systemd is required"
command -v apt-get >/dev/null || die "only Debian/Ubuntu (apt) are supported"

# nginx layout: Debian sites-available/enabled, otherwise conf.d
if [ -d /etc/nginx/sites-available ] || [ ! -d /etc/nginx/conf.d ]; then
  SITE=/etc/nginx/sites-available/payam; SITE_LINK=/etc/nginx/sites-enabled/payam
else
  SITE=/etc/nginx/conf.d/payam.conf; SITE_LINK=""
fi

# ------------------------------------------------------------------ uninstall
if [ "$UNINSTALL" = 1 ]; then
  say "removing Payam"
  systemctl disable --now payam 2>/dev/null || true
  rm -f "$UNIT"; systemctl daemon-reload
  old_domain=$(sed -n 's/^ *server_name \([^ ;]*\);.*/\1/p' "$SITE" 2>/dev/null | head -1 || true)
  rm -f "$SITE" ${SITE_LINK:+"$SITE_LINK"} /etc/letsencrypt/renewal-hooks/deploy/payam-reload-nginx.sh
  if command -v nginx >/dev/null && nginx -t 2>/dev/null; then systemctl reload nginx; fi
  rm -rf "$PREFIX" "$WEB" "$ACME"
  if [ "$PURGE" = 1 ]; then
    rm -rf "$DATA"
    [ -n "$old_domain" ] && certbot delete --non-interactive --cert-name "$old_domain" 2>/dev/null || true
    userdel payam 2>/dev/null || true
    say "data and certificate deleted"
  else
    say "accounts kept in $DATA (use --uninstall --purge to delete)"
  fi
  exit 0
fi

# ------------------------------------------------------------------ inputs
if [ -z "$DOMAIN" ] && [ -r /dev/tty ]; then
  printf 'Domain for the messenger (e.g. chat.example.com): ' > /dev/tty
  read -r DOMAIN < /dev/tty
fi
DOMAIN=$(printf '%s' "$DOMAIN" | tr 'A-Z' 'a-z')
printf '%s' "$DOMAIN" | grep -Eq '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$' || { usage; die "a valid --domain is required"; }

case "$(uname -m)" in
  x86_64) NODE_ARCH=x64 ;;
  aarch64|arm64) NODE_ARCH=arm64 ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac

port_used() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }

if [ -z "$PORT" ] && [ -f "$UNIT" ]; then PORT=$(sed -n 's/.*PORT=\([0-9]*\).*/\1/p' "$UNIT" | head -1); fi
if [ -z "$PORT" ]; then
  for p in $(seq 8095 8195); do port_used "$p" || { PORT=$p; break; }; done
  [ -n "$PORT" ] || die "no free local port in 8095-8195 (use --port)"
elif ! systemctl is-active --quiet payam && port_used "$PORT"; then
  die "port $PORT is already used by another program"
fi

TOTAL_MB=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
[ -n "$MAX_RAM" ] || MAX_RAM=$(( TOTAL_MB / 4 > 1024 ? 1024 : TOTAL_MB / 4 ))
[ "$MAX_RAM" -ge 64 ] || MAX_RAM=64
MEM_MAX=$(( MAX_RAM + 250 ))

# ------------------------------------------------------------------ conflict checks (before changing anything)
if ! command -v nginx >/dev/null; then
  for p in 80 443; do
    if port_used "$p"; then
      die "port $p is used by another web server ($(ss -Hltnp "sport = :$p" | grep -o 'users:(("[^"]*' | cut -d'"' -f2 | head -1)). Payam needs nginx on 80/443; put it behind your existing server manually or free the port."
    fi
  done
fi
if [ -d /etc/nginx ]; then
  clash=$(grep -rlE "server_name[^;]*[[:space:]]${DOMAIN//./\\.}([[:space:];]|$)" /etc/nginx/ 2>/dev/null | grep -v -e "^$SITE\$" ${SITE_LINK:+-e "^$SITE_LINK\$"} || true)
  [ -z "$clash" ] || die "$DOMAIN is already configured in: $clash — choose another (sub)domain"
fi

# ------------------------------------------------------------------ packages (only what's missing)
need=()
for c in curl:curl xz:xz-utils tar:tar nginx:nginx; do command -v "${c%%:*}" >/dev/null || need+=("${c#*:}"); done
[ "$NO_TLS" = 1 ] || command -v certbot >/dev/null || need+=(certbot)
if [ ${#need[@]} -gt 0 ]; then
  say "installing: ${need[*]}"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends ca-certificates "${need[@]}" >/dev/null
fi
systemctl enable --now nginx >/dev/null 2>&1 || true

# ------------------------------------------------------------------ Node.js (private copy)
fetch() { curl -fsSL --retry 4 --connect-timeout 20 "$@"; }
if [ "$("$PREFIX/node/bin/node" -v 2>/dev/null || true)" != "$NODE_VERSION" ]; then
  say "installing Node.js $NODE_VERSION (private, in $PREFIX/node)"
  f="node-$NODE_VERSION-linux-$NODE_ARCH.tar.xz"
  tmp=$(mktemp -d)
  ok=0
  for base in "https://nodejs.org/dist/$NODE_VERSION" "https://registry.npmmirror.com/-/binary/node/$NODE_VERSION"; do
    if fetch -o "$tmp/$f" "$base/$f" && fetch -o "$tmp/SHASUMS256.txt" "$base/SHASUMS256.txt" \
       && (cd "$tmp" && grep " $f\$" SHASUMS256.txt | sha256sum -c - >/dev/null); then ok=1; break; fi
  done
  [ "$ok" = 1 ] || die "could not download Node.js (checked nodejs.org and npmmirror.com)"
  rm -rf "$PREFIX/node"; mkdir -p "$PREFIX/node"
  tar -xJf "$tmp/$f" -C "$PREFIX/node" --strip-components=1
  rm -rf "$tmp"
fi

# ------------------------------------------------------------------ application
id payam >/dev/null 2>&1 || useradd --system --home-dir "$PREFIX" --shell /usr/sbin/nologin payam
say "installing the server"
stage=$(mktemp -d)
here=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || true)
if [ -n "$here" ] && [ -f "$here/server/server.js" ]; then
  cp "$here"/server/{server.js,package.json,package-lock.json} "$stage/"
else
  fetch "https://codeload.github.com/$REPO/tar.gz/$REF" | tar -xz -C "$stage" --strip-components=2 --wildcards '*/server/server.js' '*/server/package.json' '*/server/package-lock.json' \
    || die "could not download the source from GitHub. Clone the repo and run ./install.sh from it instead."
fi
export PATH="$PREFIX/node/bin:$PATH"
( cd "$stage" && { npm ci --omit=dev --no-audit --no-fund --loglevel=error \
    || npm ci --omit=dev --no-audit --no-fund --loglevel=error --registry=https://registry.npmmirror.com; } ) >/dev/null \
  || die "npm install failed"
rm -rf "$PREFIX/app.old"; [ -d "$PREFIX/app" ] && mv "$PREFIX/app" "$PREFIX/app.old"
mv "$stage" "$PREFIX/app"
chown -R root:root "$PREFIX"; chmod -R go-w "$PREFIX"; chmod 755 "$PREFIX" "$PREFIX/app"

cat > "$UNIT" <<EOF
# Payam messenger relay — generated by install.sh (re-run it to update).
[Unit]
Description=Payam messenger relay
After=network.target

[Service]
User=payam
Group=payam
WorkingDirectory=$PREFIX/app
ExecStart=$PREFIX/node/bin/node server.js
Environment=PORT=$PORT HOST=127.0.0.1 MAX_RAM_MB=$MAX_RAM DB_PATH=$DATA/users.db
StateDirectory=payam
StateDirectoryMode=0700
Restart=always
RestartSec=3
# The server logs nothing about users; drop stdout anyway, keep crashes in the journal.
StandardOutput=null
StandardError=journal
# Never starve other services; queued ciphertext must never be swapped to disk.
MemoryMax=${MEM_MAX}M
MemorySwapMax=0
CPUWeight=50
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable payam >/dev/null 2>&1
systemctl restart payam
rm -rf "$PREFIX/app.old"

# ------------------------------------------------------------------ nginx + TLS
mkdir -p "$WEB" "$ACME"
CERT=/etc/letsencrypt/live/$DOMAIN

V6=""; [ -f /proc/net/if_inet6 ] && V6=1   # only listen on IPv6 when the kernel has it

proxy_block() {
  cat <<EOF
    # No traffic logs: who connects when is exactly what we promise not to keep.
    access_log off;
    error_log /var/log/nginx/payam-error.log crit;
    client_max_body_size 101m;
    proxy_request_buffering off;
    proxy_buffering off;

    location ^~ /.well-known/acme-challenge/ { root $ACME; }
    location /ws {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_read_timeout 120s;
    }
    # Android app: served from here if present, otherwise the latest GitHub release.
    location = /app.apk { root $WEB; default_type application/vnd.android.package-archive; try_files /app.apk @release; }
    location @release { return 302 https://github.com/$REPO/releases/latest; }
    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}
EOF
}

write_site() { # $1 = acme (challenge only) | http | https
  local tmp; tmp=$(mktemp)
  {
    echo "# Payam messenger — $DOMAIN → 127.0.0.1:$PORT. Generated by install.sh; re-run it instead of editing."
    echo "server {"
    echo "    listen 80;"
    [ -z "$V6" ] || echo "    listen [::]:80;"
    echo "    server_name $DOMAIN;"
    case "$1" in
      acme)
        echo "    access_log off;"
        echo "    location ^~ /.well-known/acme-challenge/ { root $ACME; }"
        echo "    location / { return 503; }"
        echo "}" ;;
      http) proxy_block ;;
      https)
        echo "    access_log off;"
        echo "    location ^~ /.well-known/acme-challenge/ { root $ACME; }"
        echo "    location / { return 301 https://\$host\$request_uri; }"
        echo "}"
        echo "server {"
        echo "    listen 443 ssl;"
        [ -z "$V6" ] || echo "    listen [::]:443 ssl;"
        echo "    server_name $DOMAIN;"
        echo "    ssl_certificate     $CERT/fullchain.pem;"
        echo "    ssl_certificate_key $CERT/privkey.pem;"
        echo "    ssl_protocols TLSv1.2 TLSv1.3;"
        proxy_block ;;
    esac
  } > "$tmp"
  local backup=""; [ -f "$SITE" ] && { backup=$(mktemp); cp "$SITE" "$backup"; }
  mv "$tmp" "$SITE"; chmod 644 "$SITE"
  [ -z "$SITE_LINK" ] || ln -sf "$SITE" "$SITE_LINK"
  if ! nginx -t >/dev/null 2>&1; then
    if [ -n "$backup" ]; then mv "$backup" "$SITE"; else rm -f "$SITE" ${SITE_LINK:+"$SITE_LINK"}; fi
    nginx -t || true
    die "nginx rejected the configuration; your previous configuration was restored"
  fi
  [ -z "$backup" ] || rm -f "$backup"
  systemctl reload nginx
}

if [ "$NO_TLS" = 1 ]; then
  write_site http
  URL="http://$DOMAIN"
else
  if [ ! -f "$CERT/fullchain.pem" ]; then
    write_site acme
    say "requesting a Let's Encrypt certificate for $DOMAIN"
    acct=(--register-unsafely-without-email); [ -z "$EMAIL" ] || acct=(--email "$EMAIL")
    certbot certonly --webroot -w "$ACME" -d "$DOMAIN" --cert-name "$DOMAIN" --reuse-key --key-type ecdsa \
      --non-interactive --agree-tos "${acct[@]}" >/dev/null 2>&1 || die "could not get a certificate.
  Check that $DOMAIN points to this server's public IP (not through a CDN proxy)
  and that port 80 is reachable from the internet, then run the installer again."
  fi
  # --reuse-key keeps the server key stable across renewals: the app pins it on first use.
  mkdir -p /etc/letsencrypt/renewal-hooks/deploy
  printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/payam-reload-nginx.sh
  chmod 755 /etc/letsencrypt/renewal-hooks/deploy/payam-reload-nginx.sh
  write_site https
  URL="https://$DOMAIN"
fi

# Ship the latest released Android app from this server (best effort).
if fetch -o "$WEB/app.apk.new" "https://github.com/$REPO/releases/latest/download/payam.apk" 2>/dev/null; then
  mv "$WEB/app.apk.new" "$WEB/app.apk"
  sha256sum "$WEB/app.apk" | cut -d' ' -f1 > "$WEB/app.apk.sha256"
else
  rm -f "$WEB/app.apk.new"
fi

# ------------------------------------------------------------------ health check
for _ in $(seq 1 15); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/u/aaaaaaaa" || true)
  [ "$code" = 200 ] && break
  sleep 1
done
[ "$code" = 200 ] || { journalctl -u payam -n 20 --no-pager >&2 || true; die "the service did not start"; }

if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw status | grep -qE '^(80|443|Nginx Full)' || warn "ufw is active: allow web traffic with:  ufw allow 'Nginx Full'"
fi

cat <<EOF

  ✅ Payam is running.

     Server address (enter it in the app):  $URL
     Android app download:                  $URL/app.apk
     Service: payam (port $PORT, RAM for queued messages: ${MAX_RAM} MB)

     Update:     run the same install command again
     Uninstall:  install.sh --uninstall   (accounts kept; add --purge to delete them)

EOF
