---
name: java-patterns-database
description: Use BEFORE writing any entity, repository, schema, or query. Covers query-first database design, relationships (1-1, 1-N, N-N) with proper FKs and indexes, audit/soft-delete/versioning conventions, Spring Data JPA patterns (Repository, Specification, Projection, EntityGraph), N+1 prevention, and when to drop to jOOQ.
---

# Database Design Patterns + Spring Data

## 1. Design Order — Query First

Always design in this order:
1. **List the queries** the feature must answer. Be specific: "Top 10 orders per tenant in the last 24h, by total desc."
2. **Sketch the access pattern** — by what key, sorted by what, filtered by what, with what cardinality.
3. **Derive the tables and relationships** from #1 + #2. The schema serves the queries, not the other way around.
4. **Place indexes** to make #1 cheap. Every FK indexed. Compound indexes follow query predicate order.
5. **Write the migration** (Flyway), then the entities, then the repository, then the query.

Never start from "what entities do I have" — that produces normalized schemas that can't answer the actual questions efficiently.

## 2. Tables — Canonical Conventions

### Naming
- `snake_case`. Tables plural (`orders`, `order_lines`). Columns singular.
- PK is `id` of type `UUID` (default) or `BIGINT` (when ordering matters and tenant scope is tight).
- FK columns: `<referenced_table_singular>_id` (`customer_id`, not `customer_fk`).
- Booleans: `is_*` / `has_*`.
- Timestamps in UTC, type `TIMESTAMPTZ`.

### Mandatory Columns on every business table
```sql
id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
tenant_id    UUID         NOT NULL,   -- omit only if explicitly cross-tenant (rare; e.g. public catalog)
created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
created_by   TEXT         NOT NULL,
updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
updated_by   TEXT         NOT NULL,
version      BIGINT       NOT NULL DEFAULT 0   -- optimistic locking
-- soft delete (only when business needs to recover deletions)
deleted_at   TIMESTAMPTZ  NULL,
deleted_by   TEXT         NULL
```
Triggers (or JPA `@PrePersist`/`@PreUpdate` + an auditor) maintain `updated_at`/`updated_by`.

### IDs
- Default: `UUID v7` (time-ordered) — combines randomness with index locality. Generate in app, not DB, so events can carry the ID before the row is written.
- `BIGINT IDENTITY` only when you need ordering, fit in 8 bytes matters, and IDs never leave one service.

### Soft Delete
Add `deleted_at` + filter on `deleted_at IS NULL` in every read. Index: `CREATE INDEX ... WHERE deleted_at IS NULL`. **Do not** add soft delete by default — only when the domain requires recovery.

## 3. Relationships

### One-to-One
- Use when lifecycles are coupled and the second table is too wide for the first.
- Implement: child table's PK is also FK to parent (`PRIMARY KEY (parent_id)`).
- In JPA: `@OneToOne(mappedBy=..., fetch = LAZY)` + `@MapsId`.

### One-to-Many
- The "many" side carries the FK.
- **Always index the FK column.**
- In JPA: `@ManyToOne(fetch = LAZY)` on the many side. Avoid `@OneToMany` on the parent unless you genuinely need cascades; if you do, set `fetch = LAZY` and never collection-render in toString.

### Many-to-Many
- Always model as an explicit join entity with its own columns (created_at, role, etc.) — never rely on `@ManyToMany` with a hidden join table. Even an empty join entity pays for itself the day you need an extra column.

```sql
CREATE TABLE order_items (
  order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES products(id),
  qty        INT  NOT NULL CHECK (qty > 0),
  unit_price NUMERIC(19,4) NOT NULL,
  PRIMARY KEY (order_id, product_id)
);
CREATE INDEX ix_order_items_product ON order_items(product_id);
```

## 4. Indexing Strategy

Defaults:
- **Every FK** is indexed.
- **Compound indexes** match the WHERE/ORDER BY of the hottest queries (`(tenant_id, status, created_at DESC)`).
- **Partial indexes** for filtered queries: `CREATE INDEX ... WHERE deleted_at IS NULL`.
- **Covering indexes** with `INCLUDE` for read-heavy projections.
- **Unique constraints** model invariants (`UNIQUE (tenant_id, email)`).
- Avoid indexes on low-cardinality columns alone (booleans, `status`); combine with a high-cardinality column.

Confirm with `EXPLAIN (ANALYZE, BUFFERS)` for hot queries before merging.

## 5. Constraints — Database Enforces, Java Reinforces

- `NOT NULL` everywhere possible.
- `CHECK` constraints for ranges and enum-like fields (`CHECK (status IN ('DRAFT','PLACED',...))`).
- `FOREIGN KEY` with explicit `ON DELETE` policy (`CASCADE`, `RESTRICT`, `SET NULL`) — never default.
- `UNIQUE` for business identity (e.g., `UNIQUE (tenant_id, slug)`).

Bean Validation (`@NotNull`, `@Size`, `@Email`) on entities reinforces the DB but does not replace it.

## 6. Money, Time, JSON

- **Money**: `NUMERIC(19,4)` + a `currency CHAR(3)` column, or a value object `Money(amount, currency)`. **Never** `DOUBLE` for money.
- **Time**: `TIMESTAMPTZ`. Application uses `Instant` (UTC) — convert to user TZ only at presentation.
- **JSON**: `JSONB`. Use sparingly — for genuinely schema-less attributes. Otherwise model columns.

## 7. Spring Data JPA — Patterns

### Repository
```java
public interface OrderRepository extends JpaRepository<Order, OrderId>, JpaSpecificationExecutor<Order> {
    Optional<Order> findByTenantIdAndId(TenantId tenant, OrderId id);
    @EntityGraph(attributePaths = {"lines", "customer"})
    List<Order> findTop10ByTenantIdAndStatusOrderByCreatedAtDesc(TenantId t, OrderStatus s);
}
```
- One repository per aggregate root. **Never** a generic `BaseRepository<Object>`.
- Method-name queries for ≤3 predicates. For more, use `@Query` or Specification.

### Specification (dynamic filtering)
```java
public final class OrderSpecs {
    public static Specification<Order> byTenant(TenantId t) {
        return (r, q, cb) -> cb.equal(r.get("tenantId"), t);
    }
    public static Specification<Order> byStatus(OrderStatus s) {
        return (r, q, cb) -> cb.equal(r.get("status"), s);
    }
    public static Specification<Order> createdAfter(Instant since) {
        return (r, q, cb) -> cb.greaterThan(r.get("createdAt"), since);
    }
}
// repo.findAll(byTenant(t).and(byStatus(PLACED)).and(createdAfter(since)), page)
```

### Projection (return only needed columns)
```java
public interface OrderSummary {
    UUID getId();
    String getCustomerName();
    BigDecimal getTotal();
}
List<OrderSummary> findByTenantId(TenantId t, Pageable p);
```

### EntityGraph (N+1 prevention)
For "load aggregate with all needed associations" use `@EntityGraph(attributePaths = {...})` on the query method or named graph on the entity. **Don't** mark associations `EAGER` to fix N+1 — fix it per-query.

### Batch Operations
- `spring.jpa.properties.hibernate.jdbc.batch_size=50`
- `spring.jpa.properties.hibernate.order_inserts=true`
- For bulk update/delete: `@Modifying @Query("update ... where ...")` or jOOQ.

### Transactions
- `@Transactional` belongs in the **application layer** (use-cases), not on repositories or controllers.
- `readOnly = true` on query use-cases.
- Default propagation `REQUIRED`. Use `REQUIRES_NEW` only with a clear rationale.

## 8. When to Drop to jOOQ

JPA is bad at: dynamic SQL, window functions, CTEs, complex aggregations, bulk operations, vendor-specific features (Postgres `JSONB`, `GENERATED`, `LATERAL` joins).

For these, use **jOOQ** (alongside JPA — they coexist):
```java
var top = ctx.select(ORDERS.ID, ORDERS.TOTAL,
                     rowNumber().over(partitionBy(ORDERS.CUSTOMER_ID).orderBy(ORDERS.TOTAL.desc())).as("rn"))
            .from(ORDERS).where(ORDERS.TENANT_ID.eq(tid))
            .qualify(field("rn").eq(1))
            .fetch();
```

## 9. Search & Full-Text

### 9.1 Decision matrix — Postgres FTS vs dedicated search

| Use Postgres FTS when… | Use OpenSearch / Elasticsearch when… |
| --- | --- |
| Row count up to ~10M, growth predictable | ≥100M docs, sustained ingest, fast growth |
| Simple keyword + boolean + prefix queries | Faceting, aggregations on big indexes, custom scoring |
| Tight consistency required (search reflects DB) | Eventual consistency is acceptable |
| Operating one DB is the win | Already running OpenSearch; ops can handle a second store |
| Tenants need per-tenant isolation easily (search_path) | Per-tenant indexes / aliases |
| Languages you need are supported by `pg_catalog.*` configs | Language coverage / analyzers more important than infra simplicity |

**Default = Postgres FTS.** Switch only when measurements justify it.

### 9.2 Postgres FTS — pattern

Schema:
```sql
ALTER TABLE products
    ADD COLUMN search_tsv tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(name, '')),        'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(brand, '')),       'C')
    ) STORED;

CREATE INDEX ix_products_search_tsv ON products USING GIN (search_tsv);
```

Query:
```sql
SELECT id, name, ts_rank_cd(search_tsv, query) AS score
  FROM products, websearch_to_tsquery('simple', :q) query
 WHERE search_tsv @@ query
   AND tenant_id = :tenant_id      -- multi-tenant predicate (or implicit via search_path)
 ORDER BY score DESC, id
 LIMIT :limit;
```

Notes:
- `websearch_to_tsquery` accepts user-friendly syntax (quotes, OR, -negation).
- `to_tsvector('simple', ...)` keeps it language-neutral; switch to `'english'`/`'french'` configs when you need stemming.
- `GENERATED ALWAYS AS ... STORED` keeps the tsvector in sync automatically — no triggers needed.
- Add `trigram` extension (`pg_trgm`) for prefix / fuzzy matching: `WHERE name % :q` with a GIN trigram index.

### 9.3 Trigram for "starts with" / fuzzy

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_products_name_trgm ON products USING GIN (lower(name) gin_trgm_ops);
-- query
SELECT id, name FROM products
 WHERE tenant_id = :tenant_id AND lower(name) ILIKE :prefix || '%'
 ORDER BY name LIMIT 20;
```

### 9.4 Spring Data integration

Avoid building tsvectors / tsqueries with Spring Data method names — drop to a native query or jOOQ for clarity:

```java
public interface ProductSearchRepository {
    List<ProductHit> search(TenantId tenant, String query, int limit);
}

@Repository
class ProductSearchJdbcRepository implements ProductSearchRepository {
    private final JdbcTemplate jdbc;
    public List<ProductHit> search(TenantId tenant, String query, int limit) {
        return jdbc.query("""
            SELECT id, name, ts_rank_cd(search_tsv, websearch_to_tsquery('simple', ?)) AS score
              FROM products
             WHERE tenant_id = ? AND search_tsv @@ websearch_to_tsquery('simple', ?)
             ORDER BY score DESC, id
             LIMIT ?
            """, (rs, i) -> new ProductHit(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getDouble("score")),
            query, tenant.value(), query, limit);
    }
}
```

### 9.5 OpenSearch / Elasticsearch — when you switch

Pattern:
1. Operational writes go to Postgres (source of truth, see Outbox §java-messaging).
2. Service publishes domain events (`product.upserted.v1`).
3. A **search-indexer** consumer subscribes and upserts to OpenSearch.
4. Reads for search → OpenSearch. Reads for "give me the row by id" → Postgres. Never let UI assume OpenSearch is consistent with Postgres in the same view.

Rules:
- **Never** write to OpenSearch directly from the use-case alongside Postgres — dual-write inconsistency. Outbox + indexer is the only sanctioned path.
- Index per tenant when tenants are isolated and large; otherwise one index with `tenant_id` field + filter.
- Aliased indexes (`products_v3`) so you can reindex without downtime.
- Reindex job is a backfill (see `java-migrations` §"Data Backfill Patterns").

### 9.6 Search relevance — caution

- Test with a known set of queries; relevance regressions break product UX silently.
- Track `search_zero_results_total` metric per tenant — sudden spike = relevance regression.
- Persist top-N queries per tenant for analytics + tuning.

### 9.7 Multi-tenant in search

- Postgres FTS: tenant predicate in every query + schema-per-tenant means free isolation.
- OpenSearch: index-per-tenant (preferred for >100 tenants with isolation needs) OR one index with `tenant_id` filter. Pick by the same isolation rationale as DB choice.
- Never allow a search query without a tenant filter — even an internal search across tenants is a security event.

### 9.8 Pre-merge checklist (search)
- [ ] Default to Postgres FTS until measurement says otherwise
- [ ] tsvector column is `GENERATED ALWAYS AS ... STORED`; GIN index present
- [ ] Multi-tenant predicate in every search query
- [ ] If using OpenSearch, indexer is event-driven via outbox — no dual-write
- [ ] Reindex strategy documented (aliases, backfill pattern)

## 10. Anti-patterns — Refuse

- **`spring.jpa.hibernate.ddl-auto=update` or `create`** in any environment. **`validate` in prod, `none` in tests with Flyway-managed schema.**
- **EAGER associations** by default.
- **`@OneToMany` without `mappedBy`** (creates a redundant join table).
- **Open Session In View (`open-in-view: true`)** — hides N+1, leaks transactions to controllers.
- **Storing money in `DOUBLE` / `FLOAT`**.
- **Local-time columns (`TIMESTAMP WITHOUT TIME ZONE`)**.
- **Mass-fetching for filtering in Java** — push filters to SQL.
- **`findAll()` on tables that can grow.** Always paginate.
- **No `tenant_id` on a business table** in a multi-tenant service.
- **Mutable JPA entities exposed through controllers** — map to DTOs.
- **`LIKE '%foo%'` on big tables** instead of FTS / trigram.
- **Dual-write to Postgres + OpenSearch** from the use-case.
- **OpenSearch as source of truth** for any business decision.
- **Search query lacking a tenant predicate.**

## 11. Pre-merge Checklist

- [ ] Hot queries have `EXPLAIN ANALYZE` showing index usage.
- [ ] All FKs have indexes.
- [ ] `tenant_id` present and indexed where required.
- [ ] Audit columns + `version` present.
- [ ] No `EAGER` associations introduced.
- [ ] `@Transactional` only at application layer.
- [ ] Flyway migration ships with the entity change.
- [ ] Repository tests use Testcontainers Postgres (not H2).
