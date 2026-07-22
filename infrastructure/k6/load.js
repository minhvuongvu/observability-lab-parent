// ---------------------------------------------------------------------------
// Load - a sustained ramp to a target arrival rate.
//
// The default scenario for "giả lập tải cao". It holds a constant *arrival
// rate* rather than a constant number of users, which is the distinction that
// makes the results mean anything: with fixed VUs, a system that slows down
// receives less traffic, so it hides its own degradation. With a fixed arrival
// rate the offered load is unaffected by how the system copes, and a saturated
// service shows up as growing latency and a growing VU count instead.
//
//     ../../scripts/load.sh load                     # 10 orders/s for 5m
//     RATE=20 DURATION=10m ../../scripts/load.sh load
//
// What to watch while it runs, in Grafana:
//   HTTP        p95/p99 latency, error rate, requests in flight
//   JVM         heap after GC, GC pause time, Tomcat busy threads
//   Databases   Hikari pool utilisation and pending threads
//   Kafka       producer send rate and consumer lag on inventory-updated
//   Profiles    the flame graph reshapes as the bottleneck moves
// ---------------------------------------------------------------------------
import { getToken, seedStock, placeAndReadOrder, thresholds } from './lib/lab.js';

// Ten, and the number is measured rather than chosen.
//
// The Order Service runs with a 10-connection Hikari pool and a 1.5-CPU limit,
// both deliberately tight so that saturation is visible (see .env). Measured on
// that configuration: 10/s sustains with p95 ~90ms and no errors; 20/s already
// returns 28% errors, all of them "Connection is not available, request timed
// out after 3000ms" with ~130 requests queued on the pool.
//
// This scenario is supposed to be the one the system *handles*, with thresholds
// that mean something when they pass. Breaking it is what stress.js is for. If
// you raise the pool size or the CPU limit, re-measure and raise this with it.
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '5m';
const RAMP = __ENV.RAMP || '1m';

export const options = {
  scenarios: {
    orders: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      // Headroom, not a target. k6 allocates VUs to sustain the rate; if it
      // runs out it reports "insufficient VUs" and quietly stops offering the
      // configured load - which looks like the system coping.
      preAllocatedVUs: Math.max(50, RATE * 2),
      maxVUs: Math.max(200, RATE * 20),
      stages: [
        // Ramp rather than a step, so the JIT warms up and the connection
        // pools fill before the measurement window. A cold JVM's first thousand
        // requests are not representative of anything.
        { target: RATE, duration: RAMP },
        { target: RATE, duration: DURATION },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: thresholds,
  tags: { scenario: 'load' },
};

export function setup() {
  const token = getToken('manager');
  seedStock(token);
  return { token };
}

export default function (data) {
  placeAndReadOrder(data.token);
}
