// ---------------------------------------------------------------------------
// Stress - climb until something gives.
//
// Load finds out whether the system handles the expected traffic. Stress finds
// out what it does when it cannot: which resource runs out first, whether the
// failure is graceful, and whether the alerts fire before a human notices.
//
//     ../../scripts/load.sh stress
//     PEAK=300 ../../scripts/load.sh stress
//
// No thresholds on latency or errors. The run is *supposed* to break the system,
// so failing it for breaking would say nothing. What matters is the shape of
// the failure, which is read in Grafana rather than in k6's summary:
//
//   Graceful     latency climbs, then 429/503 appear while the JVM stays alive
//                and recovers when the load drops. Bounded queues doing their job.
//   Ungraceful   heap fills, GC pauses lengthen, the container is OOM-killed,
//                and orders that were accepted are lost.
//
// The gRPC path is the interesting part. Under RESOURCE_EXHAUSTED the circuit
// breaker should open on the slow-call threshold and the Order Service should
// fall through to the Kafka path - accepting orders as PENDING rather than
// failing them. If order acceptance drops, that fallback is not working.
// ---------------------------------------------------------------------------
import { getToken, seedStock, placeAndReadOrder } from './lib/lab.js';

// Ten times the rate this system sustains (see load.js), which is enough to
// break it thoroughly without spending the run generating load that is simply
// dropped. The first failures appear around 15-20/s; everything above that is
// measuring how the failure behaves, not where it starts.
const PEAK = Number(__ENV.PEAK || 100);

export const options = {
  scenarios: {
    orders: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: Math.max(1000, PEAK * 5),
      stages: [
        { target: Math.round(PEAK * 0.25), duration: '1m' },
        { target: Math.round(PEAK * 0.5), duration: '2m' },
        { target: PEAK, duration: '2m' },
        // Hold at the peak. The interesting failures are the ones that need
        // time to develop - a heap that fills, a queue that backs up, a
        // connection pool that never recovers - and a ramp that turns around
        // immediately never reaches them.
        { target: PEAK, duration: '3m' },
        // Then release, and watch recovery. A system that does not come back
        // once the load stops has a failure that outlives its cause, which is
        // far worse than one that merely sheds traffic.
        { target: 0, duration: '2m' },
      ],
    },
  },
  thresholds: {
    // One assertion only, and it is about the load generator rather than the
    // system: if k6 could not sustain the offered rate then the numbers
    // describe k6's limits, not the platform's.
    dropped_iterations: ['count<1000'],
  },
  tags: { scenario: 'stress' },
};

export function setup() {
  const token = getToken('manager');
  seedStock(token);
  return { token };
}

export default function (data) {
  placeAndReadOrder(data.token);
}
