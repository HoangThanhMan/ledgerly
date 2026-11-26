// Flash sale: many buyers arrive within seconds for one ticket class that has far fewer tickets than buyers.
// Each buyer sends one order, for 1 or 2 tickets, paid from a wallet that holds enough money.
//
//   python3 -I perf/flash-sale-seed.py --run-id <id> --out build/k6-raw/buyers.json
//   docker run --rm --network host -v "$PWD/perf:/perf:ro" -v "$PWD/build/k6-raw:/out" -e RUN_ID=<id> \
//     grafana/k6:2.3.0 run /perf/k6/flash-sale.js
//
// Environment: BASE_URL (http://localhost:8080), BUYERS_FILE (/out/buyers.json, written by the seeding script),
// RATE (2000 buyers per second), MAX_VUS (2000), RUN_ID (makes the idempotency keys of this run unique).
//
// Arrivals are open-loop: buyer i is started on schedule whether or not earlier buyers have their answer. If the
// server is so slow that MAX_VUS buyers are all still waiting, k6 cannot start the next one and counts it in
// dropped_iterations; a run that drops more than 1% of its buyers does not describe the arrival rate it claims.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS_FILE = __ENV.BUYERS_FILE || '/out/buyers.json';
const RATE = parseInt(__ENV.RATE || '2000', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const WARNINGS = 20;

// Loaded once and shared read-only by every VU. Data returned by setup() would be copied into each VU instead,
// and 20,000 tokens times 2,000 VUs does not fit in memory.
const sale = new SharedArray('sale', () => {
  const file = JSON.parse(open(BUYERS_FILE));
  return [{ ticketClassId: file.ticketClassId, capacity: file.capacity }];
})[0];
const buyers = new SharedArray('buyers', () => JSON.parse(open(BUYERS_FILE)).buyers);

const wonMs = new Trend('order_won_ms', true);
const soldOutMs = new Trend('order_sold_out_ms', true);
const otherMs = new Trend('order_other_ms', true);
const won = new Counter('orders_won');
const soldOut = new Counter('orders_sold_out');
const shed = new Counter('orders_shed');
const other = new Counter('orders_other');

export const options = {
  scenarios: {
    sale: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.ceil(buyers.length / RATE)}s`,
      preAllocatedVUs: Math.min(RATE, MAX_VUS),
      maxVUs: MAX_VUS,
    },
  },
  // No pass or fail here: the thresholds only make the summary report these metrics.
  thresholds: {
    dropped_iterations: ['count>=0'],
    orders_won: ['count>=0'],
    orders_sold_out: ['count>=0'],
    orders_shed: ['count>=0'],
    orders_other: ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// A sold-out answer is the expected outcome for most buyers, not a failed request.
http.setResponseCallback(http.expectedStatuses(201, 409));

let warned = 0;

export default function () {
  const i = exec.scenario.iterationInTest;
  if (i >= buyers.length) {
    return;
  }
  const buyer = buyers[i];
  const response = http.post(
    `${BASE_URL}/v1/orders`,
    JSON.stringify({ ticketClassId: sale.ticketClassId, walletId: buyer.walletId, quantity: 1 + (i % 2) }),
    {
      headers: {
        Authorization: `Bearer ${buyer.token}`,
        'Idempotency-Key': `${RUN_ID}-${i}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'order' },
      timeout: '30s',
    },
  );
  const duration = response.timings.duration;
  if (response.status === 201) {
    won.add(1);
    wonMs.add(duration);
  } else if (response.status === 409 && problemType(response) === '/problems/sold-out') {
    soldOut.add(1);
    soldOutMs.add(duration);
  } else if (response.status === 503) {
    shed.add(1);
    otherMs.add(duration);
  } else {
    other.add(1);
    otherMs.add(duration);
    if (warned < WARNINGS) {
      warned++;
      console.warn(`buyer ${i}: status ${response.status}, type ${problemType(response)}`);
    }
  }
}

function problemType(response) {
  try {
    return response.json('type');
  } catch (e) {
    return null;
  }
}
