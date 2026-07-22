// ---------------------------------------------------------------------------
// Smoke - one virtual user, one minute.
//
// Not a load test. It answers "is the system wired up correctly" before a real
// run is worth starting: identity, the gateway, both services, the broker and
// both databases are all on the path of a single order.
//
// Run it first. A failed smoke test that is diagnosed under 300 VUs is a much
// harder afternoon.
//
//     ../../scripts/load.sh smoke
// ---------------------------------------------------------------------------
import { sleep } from 'k6';
import { getToken, seedStock, placeAndReadOrder, thresholds } from './lib/lab.js';

export const options = {
  vus: 1,
  duration: __ENV.DURATION || '1m',
  // Tighter than the shared thresholds: one user against an idle system has no
  // excuse for a slow response, so this is the run that establishes the
  // baseline every other scenario is compared against.
  thresholds: {
    ...thresholds,
    http_req_failed: ['rate<0.001'],
    http_req_duration: ['p(95)<300'],
  },
  tags: { scenario: 'smoke' },
};

export function setup() {
  const token = getToken('manager');
  seedStock(token);
  return { token };
}

export default function (data) {
  placeAndReadOrder(data.token);
  sleep(1);
}
