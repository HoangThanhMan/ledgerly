// Baseline load test: transfers between random wallets at a constant arrival rate.
//
//   docker run --rm --network host -v "$PWD/perf:/perf" grafana/k6:2.3.0 run \
//     --summary-export /perf/results/<run>/summary.json /perf/k6/transfer-constant-rate.js
//
// Environment: BASE_URL (http://localhost:8080), RATE (300 per second), WARMUP (1m), DURATION (5m),
// WALLETS (1000), RUN_ID (a string that makes this run's idempotency keys unique, at most 22 characters:
// the longest key is RUN_ID, "-seed-" and a wallet id, and a key may be 64 characters long).
//
// The executor is open-loop: requests are started on schedule whether or not earlier ones have returned. A
// closed-loop generator waits for each response and so sends fewer requests exactly when the server is slow,
// which hides the slowness (coordinated omission).
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '300', 10);
const WARMUP = __ENV.WARMUP || '1m';
const DURATION = __ENV.DURATION || '5m';
const WALLETS = parseInt(__ENV.WALLETS || '1000', 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;

const INITIAL_BALANCE = 10000000;
const MAX_AMOUNT = 100;
// Share of requests that repeat the previous request of the same virtual user, key and body included.
const REPLAY_SHARE = 0.05;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function scenario(duration, startTime, phase) {
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: duration,
    startTime: startTime,
    preAllocatedVUs: 200,
    maxVUs: 1000,
    tags: { phase: phase },
  };
}

export const options = {
  setupTimeout: '5m',
  scenarios: {
    // Not measured: lets the JIT compile, the pools fill and the caches warm.
    warmup: scenario(WARMUP, '0s', 'warmup'),
    measure: scenario(DURATION, WARMUP, 'measure'),
  },
  thresholds: {
    'http_req_failed{phase:measure}': ['rate<0.001'],
    'http_req_duration{phase:measure,name:transfer}': ['p(95)<150', 'p(99)<200'],
    // No limit of its own: listed so that the summary reports the replay path separately.
    'http_req_duration{phase:measure,name:replay}': ['max>=0'],
    'http_reqs{phase:measure}': ['count>0'],
    // If k6 itself cannot start iterations on time, the numbers above mean nothing.
    dropped_iterations: ['count==0'],
  },
  // k6 prints no p99 unless asked.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(201));

export function setup() {
  const wallets = [];
  const batchSize = 50;
  for (let offset = 0; offset < WALLETS; offset += batchSize) {
    const count = Math.min(batchSize, WALLETS - offset);
    const opened = http.batch(
      Array.from({ length: count }, () => ({
        method: 'POST',
        url: `${BASE_URL}/v1/wallets`,
        body: JSON.stringify({ currency: 'VND' }),
        params: { headers: JSON_HEADERS, tags: { name: 'setup' } },
      })),
    );
    const ids = opened.map((response) => response.json('id'));
    const deposits = http.batch(
      ids.map((id) => ({
        method: 'POST',
        url: `${BASE_URL}/v1/admin/deposits`,
        body: JSON.stringify({ walletId: id, amount: `${INITIAL_BALANCE}`, currency: 'VND' }),
        params: {
          headers: Object.assign({ 'Idempotency-Key': `${RUN_ID}-seed-${id}` }, JSON_HEADERS),
          tags: { name: 'setup' },
        },
      })),
    );
    if (deposits.some((response) => response.status !== 201)) {
      exec.test.abort('seeding wallets failed');
    }
    wallets.push(...ids);
  }
  return { wallets: wallets };
}

// The previous request of this virtual user. Each virtual user runs in its own JavaScript runtime.
let previous = null;

export default function (data) {
  const replay = previous !== null && Math.random() < REPLAY_SHARE;
  const request = replay ? previous : newTransfer(data.wallets);
  const response = http.post(`${BASE_URL}/v1/transfers`, request.body, {
    headers: Object.assign({ 'Idempotency-Key': request.key }, JSON_HEADERS),
    tags: { name: replay ? 'replay' : 'transfer' },
  });
  check(response, {
    'status is 201': (r) => r.status === 201,
    'replay is marked as replayed': (r) => !replay || r.headers['Idempotent-Replayed'] === 'true',
  });
  previous = request;
}

function newTransfer(wallets) {
  const source = Math.floor(Math.random() * wallets.length);
  // Any wallet but the source.
  const target = (source + 1 + Math.floor(Math.random() * (wallets.length - 1))) % wallets.length;
  return {
    key: `${RUN_ID}-${exec.vu.idInTest}-${exec.vu.iterationInInstance}`,
    body: JSON.stringify({
      sourceWalletId: wallets[source],
      targetWalletId: wallets[target],
      amount: `${1 + Math.floor(Math.random() * MAX_AMOUNT)}`,
      currency: 'VND',
    }),
  };
}
