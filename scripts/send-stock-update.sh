#!/usr/bin/env bash
# Signs a stock update file like the ERP does and posts it to the stock webhook.
# Usage: scripts/send-stock-update.sh <stock-update.json> [event-id]
# Env:   ERP_WEBHOOK_SECRET (default dev-erp-webhook-secret), BASE_URL (default http://localhost:8080)
# Note:  an update whose changedAt is older than the stored one is answered 200 {"stale":true};
#        one more than 5 minutes ahead of the service's clock is rejected with 400.
set -euo pipefail

file="${1:?usage: send-stock-update.sh <stock-update.json> [event-id]}"
event_id="${2:-erp-evt-$(date +%s)-$RANDOM}"
secret="${ERP_WEBHOOK_SECRET:-dev-erp-webhook-secret}"
base_url="${BASE_URL:-http://localhost:8080}"

for tool in openssl curl; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done

# Sign the exact bytes of the file, then send that same file unchanged (--data-binary keeps every byte).
signature="sha256=$(openssl dgst -sha256 -hmac "$secret" -r < "$file" | cut -d' ' -f1)"

echo "POST $base_url/webhooks/erp/stock (event id: $event_id)"
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$base_url/webhooks/erp/stock" \
  -H "Content-Type: application/json" \
  -H "X-Erp-Event-Id: $event_id" \
  -H "X-Erp-Signature: $signature" \
  --data-binary @"$file"
