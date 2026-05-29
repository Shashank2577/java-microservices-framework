---
name: java-caching
description: Use whenever introducing a cache, deciding cache scope (local vs distributed), designing keys, choosing a TTL, or handling invalidation. Covers Caffeine vs Redis decision, tenant-scoped cache keys, stampede prevention, invalidation via domain events, and negative caching.
---

# Caching — Use Carefully

A cache is a correctness risk that earns its place by saving latency or downstream load. The default is **no cache** until a measurement shows it's needed. When you add one, follow these rules.

## 1. Decision Matrix

| Use…                          | When                                                                              |
| ----------------------------- | --------------------------------------------------------------------------------- |
| **No cache**                  | Default. Data is fresh, latency is fine, downstream is happy.                     |
| **Caffeine (in-process)**     | Read-mostly, small key space, eventual consistency is fine across replicas (≤N copies, each refreshes independently). |
| **Redis (distributed)**       | Shared across replicas, larger key space, need consistency across the fleet.      |
| **Caffeine + Redis (two-tier)** | Hot key space, very high RPS, willing to manage two layers and their invalidation. |
| **Materialized view in Postgres** | Read model that's worth persisting; refreshable on schedule or via events.    |

The right cache for the wrong reason makes outages worse, not better.

## 2. Cache Keys — Always Tenant-Scoped

```java
String key = "order:" + tenantId + ":" + orderId;
```

- **Never** a global key for tenant-scoped data. Cross-tenant leak risk.
- Include the schema/version: `order:v1:<tid>:<oid>`. Bumping `v` is a zero-downtime invalidation.
- Avoid keys that include rapidly changing input (paging cursors, timestamps to the second) — low hit rate, churn.

## 3. TTLs and Jitter

| Data shape                  | TTL                                |
| --------------------------- | ---------------------------------- |
| Read-only reference data    | 24h                                |
| Lookup tables               | 5–15 min                           |
| Per-user/per-tenant queries | 30s–2min                           |
| Authoritative writes        | **Don't cache.** Read straight from DB. |

Always add **jitter** (`±10–20%`) to TTL so a cache reload doesn't stampede when many keys expire at once.

```java
Duration ttlWithJitter(Duration base) {
    var ms = base.toMillis();
    var j = ThreadLocalRandom.current().nextLong(-(ms/10), (ms/10) + 1);
    return Duration.ofMillis(ms + j);
}
```

## 4. Caffeine — Local In-Process

```java
@Bean
Caffeine<Object, Object> caffeineConfig() {
    return Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .recordStats();
}

@Bean
CacheManager cacheManager(Caffeine<Object,Object> caffeine) {
    var mgr = new CaffeineCacheManager("orderById", "tenantSettings");
    mgr.setCaffeine(caffeine);
    return mgr;
}

@Service
class OrderQueries {
    @Cacheable(cacheNames = "orderById", key = "T(java.util.Objects).hash(#tenant, #id)")
    public OrderView byId(TenantId tenant, OrderId id) { ... }

    @CacheEvict(cacheNames = "orderById", key = "T(java.util.Objects).hash(#tenant, #id)")
    public void evict(TenantId tenant, OrderId id) {}
}
```

- Each replica has its own copy. Updates on one don't invalidate others.
- Useful for: read-mostly small caches, hot lookups, code-generated config.
- Stats: `cacheManager.getCacheNames()` + `CacheStats` exposed to Micrometer.

## 5. Redis — Distributed

```java
@Bean
RedisCacheManager redisCacheManager(RedisConnectionFactory cf) {
    var config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(2))
        .disableCachingNullValues()
        .serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
    return RedisCacheManager.builder(cf).cacheDefaults(config)
        .withCacheConfiguration("orderById", config.entryTtl(Duration.ofMinutes(5)))
        .build();
}
```

- Always set per-cache TTL — `defaultCacheConfig` is generous.
- Use `GenericJackson2JsonRedisSerializer` for readability and forward compatibility (not Java serialization).
- Tag every key with service prefix to avoid collisions: `orders:orderById:v1:<tid>:<oid>`.
- Use Redis cluster + AOF persistence in prod, not RDB-only.

## 6. Cache Stampede — Prevention

Stampede = many requests miss the same key simultaneously, all hit the backend.

| Pattern                | How                                                              |
| ---------------------- | ---------------------------------------------------------------- |
| **Jittered TTLs**      | §3 — staggers expirations across instances.                      |
| **Single-flight**      | Coalesce concurrent loads for the same key. Caffeine `LoadingCache` does this natively. |
| **Probabilistic early refresh** | Refresh before expiry with low probability — smooths the cliff. |
| **Stale-while-revalidate** | Return stale value, refresh in background. Caffeine `refreshAfterWrite`. |

```java
LoadingCache<String, OrderView> cache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .refreshAfterWrite(Duration.ofMinutes(2))     // background refresh
    .expireAfterWrite(Duration.ofMinutes(10))     // hard expiry
    .build(key -> loadFromDb(key));
```

For Redis, application-level single-flight via a short-lived per-key lock (e.g., `SETNX` with TTL).

## 7. Invalidation — The Hard Part

| Trigger                     | Pattern                                                                                              |
| --------------------------- | ---------------------------------------------------------------------------------------------------- |
| Same service writes the row | `@CacheEvict` on the write method, or evict explicitly in the use-case.                              |
| **Another service** updates | Subscribe to that service's Kafka event; evict on receipt. Tolerate eventual consistency.            |
| Bulk change                 | Evict by prefix (Redis `SCAN` + `DEL`) — never `FLUSHALL`.                                          |
| Schema change to cached shape | Bump key version (`v1` → `v2`). Old keys age out via TTL.                                          |

**Never** rely on TTL alone for "freshness" of write-impacted data. If a user changes their address, the next read should show the new address, not "in 5 minutes."

## 8. Negative Caching

If a lookup often returns "not found", consider caching the absence — but **with a much shorter TTL** than positive cache (e.g., 10s) and **always invalidate** on create. Otherwise the new resource is invisible for the TTL window.

In Spring Cache: `condition = "#result != null"` to *not* cache negatives, or `unless = "..."`.

## 9. Read-through vs Cache-aside

- **Cache-aside** (default): app reads cache; on miss, reads DB and populates cache.
- **Read-through**: cache library does the load (Caffeine `LoadingCache`, Spring `@Cacheable` with `cacheLoader`). Simpler code, slightly less control.
- **Write-through / write-behind**: write goes through cache to DB. Rarely worth the complexity in this stack. Prefer cache-aside with explicit eviction.

## 10. What NOT to Cache

- Authoritative writes' return values.
- Pages of search results that include the latest data (or invalidate on every write).
- Anything tenant-sensitive without tenant in the key.
- Per-request derived data (just memoize within the request scope).
- JWT principal — already short-lived; let Spring Security handle.

## 11. Observability for Caches

- Expose Micrometer cache metrics (`cache.gets`, `cache.size`, `cache.evictions`, hit ratio).
- Alert on hit ratio < expected threshold (sudden drop = invalidation storm or bug).
- Log evictions for high-value caches.

## 12. Anti-patterns — Refuse

- Adding a cache "to be safe" without a measurement showing latency or load needs it.
- Global TTL of 1 hour with no jitter.
- Caching across tenants (key missing `tenantId`).
- Eviction by `FLUSHALL` in shared Redis.
- Java serialization in Redis (use JSON).
- Caching mutation responses (POST/PUT/PATCH return values).
- Catching `Throwable` around cache load and "fall back to DB" silently — masks real failures.

## 13. Pre-Merge Checklist

- [ ] Measurement justifies the cache (latency or load).
- [ ] Key includes `tenantId` for tenant-scoped data, plus a version suffix.
- [ ] TTL with jitter; per-cache config, not global default.
- [ ] Stampede protection (single-flight or stale-while-revalidate) for hot keys.
- [ ] Invalidation path written down (write-side eviction or event-driven).
- [ ] Cache metrics emitted to Micrometer.
- [ ] Test verifying invalidation on write.

## 14. Reference

- Spring Cache reference: https://docs.spring.io/spring-boot/reference/io/caching.html
- Caffeine: https://github.com/ben-manes/caffeine
- Designing Data-Intensive Applications, ch. 7 — for the invariants that bite.
