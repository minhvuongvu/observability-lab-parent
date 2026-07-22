// ---------------------------------------------------------------------------
// Shared helpers for the load scenarios.
//
// Everything a scenario needs that is not the load shape itself: getting a
// token, seeding stock, building a realistic order, and the thresholds that
// decide whether a run passed.
//
// k6 runs inside lab-net, so every address here is a container name. That is
// the point of the containerisation work: the load generator experiences the
// same network the services do, rather than the host's loopback stack, which
// has different latency characteristics and no notion of the gateway.
// ---------------------------------------------------------------------------
import http from 'k6/http';
import { check, fail } from 'k6';

// Direct to the service by default, not through the edge.
//
// Kong rate-limits each route to 120 requests a minute, so a load test aimed at
// http://nginx measures the rate limiter and nothing else - every request past
// the second one in a second is a 429. That is a legitimate experiment and it
// is what BASE_URL=http://nginx is for; it is a poor default for measuring the
// service.
export const BASE_URL = __ENV.BASE_URL || 'http://order-service:8081';
export const INVENTORY_URL = __ENV.INVENTORY_URL || 'http://inventory-service:8082';
export const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://keycloak:8080';
export const REALM = __ENV.KEYCLOAK_REALM || 'observability';
export const CLIENT_ID = __ENV.KEYCLOAK_CLIENT_ID || 'swagger-ui';

// How many distinct SKUs the run spreads its orders across. Low enough that
// they stay in the Redis cache and contend on the same Oracle rows - which is
// what produces lock contention worth profiling - and high enough that the run
// is not a single hot key.
export const SKU_COUNT = Number(__ENV.SKU_COUNT || 20);

// ---------------------------------------------------------------------------
// Thresholds shared by every scenario.
//
// A load test without thresholds prints numbers; a load test with thresholds
// has an opinion, and exits non-zero when the system missed it. These match the
// SLO buckets configured on http.server.requests in application.yml, so the
// pass/fail here and the Grafana panel are asking the same question.
// ---------------------------------------------------------------------------
export const thresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<2000'],
  checks: ['rate>0.99'],
};

// ---------------------------------------------------------------------------
// OAuth2 password grant.
//
// The same flow scripts/token.sh uses, and against the same issuer: the token's
// `iss` claim is http://keycloak:8080/realms/observability whether it was minted
// from here or from the host, so both the gateway and the services accept
// either. Getting that wrong is a 401 that looks like an authorization bug and
// is actually a hostname.
// ---------------------------------------------------------------------------
export function getToken(username = 'manager', password = null) {
  const res = http.post(
    `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
    {
      client_id: CLIENT_ID,
      username: username,
      password: password || username,
      grant_type: 'password',
    },
    { tags: { name: 'keycloak-token' } },
  );

  if (res.status !== 200) {
    fail(`token request failed: ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

export function authHeaders(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };
}

export function sku(n) {
  return `LOAD-SKU-${String(n).padStart(4, '0')}`;
}

// ---------------------------------------------------------------------------
// Seeds the SKUs the run will order against.
//
// Called from setup(), which k6 runs exactly once regardless of VU count. Stock
// is generous on purpose: a run that exhausts inventory starts measuring the
// rejection path instead of the happy path, and the change is invisible in the
// aggregate numbers.
// ---------------------------------------------------------------------------
export function seedStock(token, count = SKU_COUNT) {
  const params = authHeaders(token);
  for (let i = 0; i < count; i++) {
    const res = http.post(
      `${INVENTORY_URL}/api/v1/stock`,
      JSON.stringify({ productSku: sku(i), initialQuantity: 1000000 }),
      {
        ...params,
        tags: { name: 'seed-stock' },
        // 409 means the SKU is already tracked from an earlier run, which is
        // the normal case on every run but the first. Without declaring it
        // expected here, k6 counts each one in http_req_failed - so a 20-SKU
        // seed contributed a flat 20 failures to every run, which on a short
        // scenario is ~2% and trips the `http_req_failed: rate<0.01` threshold
        // before the system under test has done anything at all.
        responseCallback: http.expectedStatuses(200, 201, 409),
      },
    );
    if (![200, 201, 409].includes(res.status)) {
      fail(`could not seed ${sku(i)}: ${res.status} ${res.body}`);
    }
  }
}

// A single-line order for one randomly chosen seeded SKU.
export function orderPayload() {
  const n = Math.floor(Math.random() * SKU_COUNT);
  return JSON.stringify({
    customerId: `LOAD-C-${__VU}`,
    currency: 'EUR',
    items: [
      {
        productSku: sku(n),
        quantity: 1 + Math.floor(Math.random() * 3),
        unitPrice: 10.5,
      },
    ],
  });
}

// ---------------------------------------------------------------------------
// One order placed, then read back.
//
// Two requests rather than one, because a write-only load test exercises none
// of the read path: no Redis cache, no cache hit ratio, and no GET latency to
// compare against the POST. Named tags keep the two separable in the metrics.
// ---------------------------------------------------------------------------
export function placeAndReadOrder(token) {
  const params = authHeaders(token);

  const created = http.post(`${BASE_URL}/api/v1/orders`, orderPayload(), {
    ...params,
    tags: { name: 'POST /api/v1/orders' },
  });

  const accepted = check(created, {
    'order accepted': (r) => r.status === 201,
  });
  if (!accepted) return;

  // 201 means accepted, not fulfilled - the order is PENDING until the
  // Inventory Service settles it over Kafka. Reading it back immediately is
  // therefore expected to show PENDING, and that is not a failure.
  const orderNumber = created.json('data.orderNumber');
  if (!orderNumber) return;

  const read = http.get(`${BASE_URL}/api/v1/orders/${orderNumber}`, {
    ...params,
    tags: { name: 'GET /api/v1/orders/{orderNumber}' },
  });

  check(read, {
    'order readable': (r) => r.status === 200,
  });
}
