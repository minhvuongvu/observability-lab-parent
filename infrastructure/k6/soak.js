// ---------------------------------------------------------------------------
// Soak - modest load, held for a long time.
//
// The only scenario that finds the class of bug that is invisible in every
// other one: things that accumulate. A leak, an unbounded cache, a connection
// that is never returned, a log file that never rotates, a Kafka consumer whose
// lag grows by one record a minute. None of them show up in five minutes, and
// all of them are outages at 04:00 on the fourth day.
//
//     ../../scripts/load.sh soak                    # 5 orders/s for 2 hours
//     DURATION=8h RATE=8 ../../scripts/load.sh soak
//
// What to read afterwards, and it is a *trend* in every case:
//
//   jvm_memory_used_bytes{area="heap"} after each full GC - the floor should be
//     flat. A rising floor is a leak, and Pyroscope's alloc_live profile names
//     the allocation site.
//   hikaricp_connections_active - should return to idle between bursts.
//   kafka_consumergroup_lag - flat, not climbing.
//   Container memory vs the JVM heap - a gap that widens is off-heap: direct
//     buffers, metaspace, or a native leak in a driver.
//
// This is the run to leave going over lunch, not the one to watch.
// ---------------------------------------------------------------------------
import { getToken, seedStock, placeAndReadOrder, thresholds } from './lib/lab.js';

// Half the sustainable rate. A soak has to leave headroom: the whole point is
// that anything which degrades over hours is visible against a flat baseline,
// and a run at the saturation point produces a noisy one.
const RATE = Number(__ENV.RATE || 5);
const DURATION = __ENV.DURATION || '2h';

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE * 2),
      maxVUs: Math.max(100, RATE * 10),
    },
  },
  // Latency thresholds are evaluated over the whole run, which flattens a slow
  // drift into an average that still passes. The threshold here is a floor
  // against outright breakage; the drift is read off the Grafana trend, which
  // is the only place it is visible.
  thresholds: {
    ...thresholds,
    http_req_duration: ['p(99)<5000'],
  },
  tags: { scenario: 'soak' },
};

export function setup() {
  const token = getToken('manager');
  seedStock(token);
  return { token };
}

export default function (data) {
  placeAndReadOrder(data.token);
}
