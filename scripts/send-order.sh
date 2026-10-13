#!/usr/bin/env bash
# Signs an order file like the shop does and posts it to the webhook.
# Usage: scripts/send-order.sh <order.json> [event-id]
# Env:   SHOP_WEBHOOK_SECRET (default dev-webhook-secret), BASE_URL (default http://localhost:8080)
set -euo pipefail

file="${1:?usage: send-order.sh <order.json> [event-id]}"
event_id="${2:-evt-$(date +%s)-$RANDOM}"
secret="${SHOP_WEBHOOK_SECRET:-dev-webhook-secret}"
base_url="${BASE_URL:-http://localhost:8080}"

for tool in openssl curl; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done

# Sign the exact bytes of the file, then send that same file unchanged (--data-binary keeps every byte).
signature="sha256=$(openssl dgst -sha256 -hmac "$secret" -r < "$file" | cut -d' ' -f1)"

echo "POST $base_url/webhooks/shop/orders (event id: $event_id)"
curl -sS -w '\nHTTP %{http_code}\n' -X POST "$base_url/webhooks/shop/orders" \
  -H "Content-Type: application/json" \
  -H "X-Shop-Event-Id: $event_id" \
  -H "X-Shop-Signature: $signature" \
  --data-binary @"$file"
