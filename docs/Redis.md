# Caching — Redis

Both services cache their hottest read. Neither caches anything else, and the reasons for *not*
caching are as much the point as the reasons for caching.

---

## 1. What is cached

| Service | Cache | Key | TTL | Why |
| --- | --- | --- | --- | --- |
| Order | `orders` | order number | 10m | A single order is read far more often than it changes — a client polling for confirmation hits it repeatedly. |
| Inventory | `stock` | product SKU | 2m | The endpoint the Order Service calls to check availability. Read constantly, changed on every reservation. |

The TTLs differ on purpose. Stock changes far more often than an order does, and a stale availability
figure is the one that oversells.

## 2. What is deliberately not cached

- **Order and stock listings.** The result depends on filters, page and sort, so the key space is
  effectively unbounded, the hit rate would be near zero, and evicting correctly on any write would
  be impossible.
- **Invoice links.** The URL embeds an expiry, so a cached one would eventually be served after it
  had already stopped working.

---

## 3. Key layout

Every key is prefixed with the service that owns it:

```
order-service:orders::ORD-20260721-A8BFF1E1
inventory-service:stock::SKU-A
```

A shared Redis stays navigable, and one service cannot collide with another's key space.

## 4. Serialisation

Values are stored as **JSON bound to a known type** — not Java serialisation, and not JSON with
embedded type information. In order of importance:

1. **Security.** A serialiser that reads a type name out of the payload and instantiates it is a
   deserialisation gadget waiting for anyone with write access to Redis. Naming the type in
   configuration means the payload can only ever become an `OrderView`.
2. **Legibility.** `redis-cli GET` returns something a human can read, which matters in a lab whose
   subject is observing what the system is doing.
3. **Evolution.** Adding a field does not invalidate cached entries the way a changed
   `serialVersionUID` would.

## 5. TTL and eviction

A TTL is **not optional** here. Without one an entry outlives its correctness and the only remedy is
flushing the cache by hand. Null values are not cached at all
(`disableCachingNullValues`) — caching "this does not exist" turns a 404 into a sticky one.

Redis itself is configured with a memory ceiling and an `allkeys-lru` policy in
[`redis.conf`](../infrastructure/redis/redis.conf), so the instance degrades by evicting cold entries
rather than by refusing writes.

### Explicit eviction, and when it runs

Every method that can change an order or a stock level evicts the same key, so the cache cannot serve
a stale status.

Reserving stock for an order changes several SKUs at once, which `@CacheEvict` cannot express — it
names one key, and `allEntries = true` would empty the cache on every order and leave it permanently
cold under load. `StockApplicationService` therefore evicts through the `CacheManager` directly.

More subtly, that eviction is deferred to **after commit**:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() { keys.forEach(cache::evict); }
});
```

Evicting inline leaves a window in which a concurrent read misses, re-reads the not-yet-committed
value, and repopulates the entry with the **old** number — so the eviction meant to fix the staleness
causes it instead. Running after commit means any read that repopulates the entry can only see the
new value. The trade is that the cache serves the previous figure for the length of the commit rather
than missing, which for a short-TTL availability cache is the better of the two.

---

## 6. Verify it

```bash
TOKEN=$(./scripts/token.sh manager | tail -1)

# Warm it, then look at what landed
curl -s http://localhost:8082/api/v1/stock/SKU-A -H "Authorization: Bearer ${TOKEN}" >/dev/null
docker exec lab-redis redis-cli --scan --pattern 'inventory-service:*'
docker exec lab-redis redis-cli GET 'inventory-service:stock::SKU-A'
docker exec lab-redis redis-cli TTL 'inventory-service:stock::SKU-A'

# Place an order for SKU-A and watch the key disappear once the reservation commits
docker exec lab-redis redis-cli --scan --pattern 'inventory-service:*'

# Hit rate and memory
docker exec lab-redis redis-cli INFO stats | grep keyspace
docker exec lab-redis redis-cli INFO memory | grep used_memory_human
```

---

## 7. What this step does not add

Redis is used here as a cache and nothing else. A **distributed lock** and a **Redis-backed rate
limiter** are both natural uses of it, but neither belongs yet: rate limiting is already enforced at
the edge by Kong (step 06), and the reservation path is currently serialised by Oracle's row locks
and optimistic versioning rather than by anything in Redis. Introducing a lock before there is
contention to demonstrate would be machinery without a failure to show — it fits the failure
simulation step, where it can be observed doing something.
