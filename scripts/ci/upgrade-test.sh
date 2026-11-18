#!/usr/bin/env bash
# End-to-end upgrade test (CI): install a release from before the rename the
# way a real NVR was installed, give it an account, a camera and a file in its
# data volume, then update it with scripts/update-linux.sh exactly as a user
# would - the OLD updater pulls, hands over to the new one, which moves the
# data and the guardian to the new name. Then check nothing was lost, and that
# a second update changes nothing.
#
# Needs: a full clone (all tags), Docker + compose, passwordless sudo, systemd.
# Usage: scripts/ci/upgrade-test.sh [FROM_TAG]   (default v0.4.23)

set -euo pipefail

FROM_TAG="${1:-v0.4.23}"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEW_SHA="$(git -C "$SRC" rev-parse HEAD)"
step() { printf '\n\033[1;35m### %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }
ok()   { printf '\033[1;32mok\033[0m  %s\n' "$1"; }

# A local "GitHub": the NVR's clone pulls from here, and the test moves main.
WORK="$(mktemp -d)"
git clone -q --bare "$SRC" "$WORK/origin.git"
git -C "$WORK/origin.git" update-ref refs/heads/main "$(git -C "$SRC" rev-parse "$FROM_TAG^{commit}")"
git clone -q -b main "$WORK/origin.git" "$WORK/nvr"
NVR="$WORK/nvr"
git -C "$NVR" fetch -q --tags origin

USER_NAME="ci-admin"
PASSWORD="Ci-$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')"  # never printed
API="https://localhost:8443/api"

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -fsk "$API/health" >/dev/null 2>&1 && return 0
    sleep 3
  done
  docker ps -a
  docker logs --tail 80 "$(docker ps -a --format '{{.Names}}' | grep -- '-backend$' | head -1)" || true
  fail "the NVR didn't become healthy"
}
login() {
  curl -fsk -X POST "$API/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
cameras() {
  curl -fsk "$API/cameras" -H "Authorization: Bearer $1" | python3 -c 'import json,sys; print(",".join(sorted(c["name"] for c in json.load(sys.stdin))))'
}

# ── 1. The old release, installed the way a real NVR was ───────────────────
step "Installing $FROM_TAG (before the rename)"
cd "$NVR"
sudo ./scripts/install-linux.sh --yes --skip-firewall
wait_healthy
# The old install's name, found the way the updater finds it: its backend container.
FORMER_NAME="$(docker inspect -f '{{.Name}}' "$(docker compose ps -a -q backend)" | sed 's#^/##; s#-backend$##')"
[[ -n "$FORMER_NAME" && "$FORMER_NAME" != imprahari ]] || fail "couldn't tell the old install's name"
FORMER_IMAGE="$(docker inspect -f '{{.Config.Image}}' "${FORMER_NAME}-backend")"
FORMER_IMAGE_PREFIX="${FORMER_IMAGE%backend:*}"
docker ps --format '{{.Names}}' | grep -qx "${FORMER_NAME}-backend" || fail "old install has no ${FORMER_NAME}-backend"
systemctl is-active --quiet "${FORMER_NAME}-guardian" || fail "old install's guardian isn't running"
ok "old install running, guardian active"

step "Giving it an account, two cameras and a file of its own"
TOKEN="$(curl -fsk -X POST "$API/auth/setup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
for name in "Front door" "Garden"; do
  curl -fsk -X POST "$API/cameras" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$name\",\"rtsp_main_url\":\"rtsp://127.0.0.1:8554/none\",\"recording_mode\":\"off\",\"area\":\"Outside\"}" >/dev/null
done
BEFORE="$(cameras "$TOKEN")"
docker exec "${FORMER_NAME}-backend" sh -c 'echo kept > /data/ci-marker.txt'
ok "cameras: $BEFORE"

# ── 2. Update, exactly as a user runs it ────────────────────────────────────
step "Publishing the new version and running the OLD updater"
git -C "$WORK/origin.git" update-ref refs/heads/main "$NEW_SHA"
./scripts/update-linux.sh --yes 2>&1 | tee "$WORK/update1.log"
wait_healthy

# ── 3. Nothing lost, everything renamed ─────────────────────────────────────
step "Checking the result"
grep -q "restarting with the new versions" "$WORK/update1.log" || fail "the old updater didn't hand over to the new one"
TOKEN="$(login)" || fail "the account from before the update can't log in"
ok "same account logs in"
AFTER="$(cameras "$TOKEN")"
[[ "$AFTER" == "$BEFORE" ]] || fail "cameras changed: '$BEFORE' -> '$AFTER'"
ok "cameras kept: $AFTER"

names="$(docker ps --format '{{.Names}}')"
grep -qx imprahari-backend <<< "$names" || fail "no imprahari-backend running"
grep -q "^${FORMER_NAME}-" <<< "$(docker ps -a --format '{{.Names}}')" && fail "containers with the previous name remain"
ok "containers: $(tr '\n' ' ' <<< "$names")"

[[ "$(docker exec imprahari-backend cat /data/ci-marker.txt)" == "kept" ]] || fail "a file in the data volume was lost"
docker exec imprahari-backend test -f /data/imprahari.db || fail "no /data/imprahari.db"
docker exec imprahari-backend test ! -e "/data/${FORMER_NAME}.db" || fail "the previous database file is still there"
[[ "$(docker exec imprahari-backend cat /data/.moved-from-previous-name)" == "$FORMER_NAME" ]] || fail "no move marker"
ok "data volume copied, database renamed"

docker volume inspect "${FORMER_NAME}-data" >/dev/null 2>&1 || fail "the old data volume (the backup) was removed"
ok "old data volume kept as a backup"

env_file="$(sudo cat "$NVR/.env")"
grep -q "^${FORMER_NAME^^}_" <<< "$env_file" && fail ".env still has ${FORMER_NAME^^}_ settings"
ok ".env settings renamed"

systemctl is-active --quiet imprahari-guardian || fail "the new guardian isn't running"
[[ ! -e "/etc/systemd/system/${FORMER_NAME}-guardian.service" ]] || fail "the previous guardian unit is still installed"
systemctl is-active --quiet "${FORMER_NAME}-guardian" && fail "the previous guardian is still running"
ok "guardian swapped"

[[ -z "$(docker network ls -q --filter "label=com.docker.compose.network=${FORMER_NAME}")" ]] || fail "the previous network remains"
[[ -z "$(docker image ls --format '{{.Repository}}' | grep -F "$FORMER_IMAGE_PREFIX" || true)" ]] || fail "previous images remain"
ok "previous network and images removed"

# ── 4. A second update is a no-op for the move ──────────────────────────────
step "Updating again"
./scripts/update-linux.sh --yes 2>&1 | tee "$WORK/update2.log"
wait_healthy
grep -q "Moving your data" "$WORK/update2.log" && fail "the second update moved data again"
TOKEN="$(login)"
[[ "$(cameras "$TOKEN")" == "$BEFORE" ]] || fail "cameras changed on the second update"
ok "second update: nothing moved, still healthy, same data"

printf '\n\033[1;32mUPGRADE TEST PASSED\033[0m (%s -> %s)\n' "$FROM_TAG" "${NEW_SHA:0:7}"
