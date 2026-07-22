// ---------------------------------------------------------------------------
// Spike - idle, then a wall of traffic, then idle again.
//
// The failure mode a gradual ramp never reproduces. A ramp gives every pool,
// cache and JIT compiler time to adapt; a spike does not, so it exercises the
// cold paths: empty connection pools filling all at once, an interpreter that
// has not compiled the hot methods yet, a cache with nothing in it, and every
// consumer rebalancing simultaneously.
//
//     ../../scripts/load.sh spike
//
// Recovery is the measurement, not the peak. Note the moment traffic stops and
// the moment p99 returns to its baseline; the gap between them is how long a
// real spike would keep hurting after it ended. Anything more than a few
// seconds usually means an unbounded queue somewhere still draining.
// ---------------------------------------------------------------------------
import { getToken, seedStock, placeAndReadOrder } from './lib/lab.js';

// Eight times the sustainable rate - a genuine wall, while still low enough
// that the recovery window afterwards is measuring recovery rather than a
// backlog the system was never going to clear.
const PEAK = Number(__ENV.PEAK || 80);

export const options = {
  scenarios: {
    orders: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: Math.max(1000, PEAK * 5),
      stages: [
        // Baseline, so there is something to compare the recovery against.
        { target: 5, duration: '1m' },
        // The spike. Ten seconds is short enough that nothing has time to
        // autoscale, warm up or rebalance - which is exactly the point.
        { target: PEAK, duration: '10s' },
        { target: PEAK, duration: '1m' },
        { target: 5, duration: '10s' },
        // Back to baseline, and left there. This window is the measurement.
        { target: 5, duration: '3m' },
      ],
    },
  },
  thresholds: {
    dropped_iterations: ['count<1000'],
  },
  tags: { scenario: 'spike' },
};

export function setup() {
  const token = getToken('manager');
  seedStock(token);
  return { token };
}

export default function (data) {
  placeAndReadOrder(data.token);
}
