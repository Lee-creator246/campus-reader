#!/usr/bin/env bash
# CI integration exercise on an isolated Linux runner with ports 80/443 free.
set -euo pipefail
archive=$(realpath "${1:?pass campus-reader-safari-server.zip}")
run=$(mktemp -d)
project="reader-smoke-$$"
export CAMPUS_DOMAIN=localhost
cd "$run"
unzip -q "$archive"
sha256sum --quiet -c SHA256SUMS
trap 'docker compose -p "$project" down --volumes || true' EXIT
docker compose -p "$project" config --quiet
docker compose -p "$project" up -d
for i in {1..60}; do
    if docker compose -p "$project" exec -T caddy cat /data/caddy/pki/authorities/local/root.crt > ca.crt 2>/dev/null &&
        curl --cacert ca.crt --fail --silent --max-time 3 https://localhost/version.json > version.json; then
        break
    fi
    sleep 1
done
curl --cacert ca.crt --fail --silent --max-time 10 https://localhost/version.json > version.json
python3 -c 'import json,os; m=json.load(open("version.json")); assert m["mode"]=="server" and m["api"] and m["commit"]==os.environ.get("GITHUB_SHA",m["commit"])'
curl --cacert ca.crt --fail --silent https://localhost/ | grep -q '北邮人信息'
status=$(curl --cacert ca.crt --silent -o message.json -w '%{http_code}' https://localhost/api/notices)
test "$status" = "503"
python3 -c 'import json; assert json.load(open("message.json"))["error"]=="public_feed_not_configured"'
cat > data/public-notices.json <<'JSON'
{"visibility":"public","notices":[{"id":"ci","title":"Synthetic check","body":"Fixture","publishedAt":"2026-11-08"}]}
JSON
curl --cacert ca.crt --fail --silent https://localhost/api/notices > notices.json
python3 -c 'import json; assert json.load(open("notices.json"))["notices"][0]["id"]=="ci"'
status=$(curl --cacert ca.crt --silent -o feedback.json -w '%{http_code}' -H 'Origin: https://localhost' \
    -H 'Content-Type: application/json' --data '{"message":"CI fixture","version":"0.9.1","commit":"abcdef0"}' https://localhost/api/feedback)
test "$status" = "201"
python3 -c 'import json; assert json.load(open("feedback.json"))["ok"]'
test "$(curl --cacert ca.crt --silent -o /dev/null -w '%{http_code}' https://localhost/api/feedback)" = "404"
echo 'Local HTTPS, site, public notice API and write-only feedback passed.'
