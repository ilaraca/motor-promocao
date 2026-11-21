#!/usr/bin/env bash
set -euo pipefail

# Generates a valid HS256 JWT signed with the local POC secret from application.yml.
# Usage:
#   ./scripts/generate-jwt.sh [userId] [scope] [segments_csv]
# Examples:
#   ./scripts/generate-jwt.sh usr_98765 "promotions:checkout" "STANDARD"
#   ./scripts/generate-jwt.sh admin_01 "promotions:admin:write" ""
#   ./scripts/generate-jwt.sh usr_vip "promotions:checkout" "STANDARD,VIP"

USER_ID="${1:-usr_98765}"
SCOPE="${2:-promotions:checkout}"
SEGMENTS_CSV="${3:-STANDARD}"
JWT_SECRET="${JWT_SECRET:-poc-super-secret-signing-key-256-bits-minimum-length-2026!!}"

python3 - "$USER_ID" "$SCOPE" "$SEGMENTS_CSV" "$JWT_SECRET" <<'PY'
import base64, hashlib, hmac, json, sys, time

user_id, scope, segments_csv, secret = sys.argv[1:5]
segments = [s.strip() for s in segments_csv.split(",") if s.strip()]
now = int(time.time())

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64url(json.dumps({
    "sub": user_id,
    "scope": scope,
    "segments": segments,
    "iat": now,
    "exp": now + 3600
}, separators=(",", ":")).encode())

signing_input = f"{header}.{payload}".encode("ascii")
sig = b64url(hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}")
PY
