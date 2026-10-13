// Load for the resilience demo: signed shop orders at a constant rate, plus 5% redeliveries of earlier events.
//
// Env: RATE (orders/s, default 10), DURATION (default 180s), RUN_ID, ORDER_BASE (first shop order id of the run),
//      SHOP_WEBHOOK_SECRET, BASE_URL, SUMMARY_PATH (where handleSummary writes the raw summary JSON).
//
// Every new order is printed as "SENT <shop order id>" before it is posted, and as "ACCEPTED <shop order id>" when the
// service answers 202: scripts/resilience-run.sh reads those lines to know, from k6's side, which orders were sent and
// which must end up in the ERP.
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const RATE = parseInt(__ENV.RATE || '10', 10);
const DURATION = __ENV.DURATION || '180s';
const RUN_ID = __ENV.RUN_ID || 'local';
const ORDER_BASE = parseInt(__ENV.ORDER_BASE || '1000000', 10);
const SECRET = __ENV.SHOP_WEBHOOK_SECRET || 'dev-webhook-secret';
const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '/results/k6-summary.json';

// Only customers seeded in customer_mapping, so no order is DEAD by design.
const CUSTOMERS = [42, 77, 105];
const SKUS = ['TORN-M6-100', 'LLAVE-ALLEN-5', 'TUERCA-M6-200'];

const accepted = new Counter('accepted');
const redelivered = new Counter('redelivered');

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function cents(value) {
  return `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`;
}

// Deterministic order for iteration n, so a redelivery sends exactly the same bytes as the first delivery.
function orderBody(n) {
  const quantity = 1 + (n % 5);
  const unitPriceCents = 1250 + (n % 3) * 250;
  return JSON.stringify({
    id: ORDER_BASE + n,
    number: String(ORDER_BASE + n),
    currency: 'EUR',
    total: cents(quantity * unitPriceCents),
    customer: { id: CUSTOMERS[n % CUSTOMERS.length] },
    line_items: [{ sku: SKUS[n % SKUS.length], quantity: quantity, price: cents(unitPriceCents) }],
  });
}

function post(n) {
  const body = orderBody(n);
  return http.post(`${BASE_URL}/webhooks/shop/orders`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Shop-Event-Id': `run-${RUN_ID}-${n}`,
      'X-Shop-Signature': `sha256=${crypto.hmac('sha256', SECRET, body, 'hex')}`,
    },
  });
}

export default function () {
  const n = exec.scenario.iterationInTest;
  // Every 20th iteration (5%) redelivers the event of iteration n - 10 (one second earlier) instead of a new order.
  if (n >= 20 && n % 20 === 0) {
    const response = post(n - 10);
    redelivered.add(1);
    check(response, { 'redelivery answered 200 duplicate': (r) => r.status === 200 });
    return;
  }
  console.log(`SENT ${ORDER_BASE + n}`);
  const response = post(n);
  const ok = check(response, { 'new order answered 202': (r) => r.status === 202 });
  if (ok) {
    accepted.add(1);
    console.log(`ACCEPTED ${ORDER_BASE + n}`);
  }
}

export function handleSummary(data) {
  return { [SUMMARY_PATH]: JSON.stringify(data, null, 2) };
}
