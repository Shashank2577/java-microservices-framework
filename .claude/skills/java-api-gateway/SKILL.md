---
name: java-api-gateway
description: Use when configuring the API gateway (Spring Cloud Gateway), routing, edge auth, rate limiting, CORS, header propagation, canary/weighted routing, request mirroring, or egress filtering for outbound LLM/3rd-party calls. The gateway is the single edge: rules live here, not duplicated in each service.
---

# API Gateway — Single Edge

The gateway is the **only** way in. Authentication, rate limiting, CORS, header sanitization, and routing live here — not duplicated across services. Internal services trust the gateway boundary because the network does (NetworkPolicy / SG).

## 1. Why one gateway

- Authentication, rate limiting, CORS, request transformation are cross-cutting. Putting them in every service = drift + bugs + each service forgets one.
- **Spring Cloud Gateway** is the default. Stateless, fast, integrates with Spring Security and OTel.
- Anti-pattern: customers calling services directly, bypassing gateway → no rate limit, no auth, no tracing. Block at the network layer (NetworkPolicy / Security Group) so the gateway is the only entrypoint.

## 2. Routing — config-driven

`application.yml`:
```yaml
spring.cloud.gateway.routes:
  - id: orders
    uri: lb://orders-svc
    predicates:
      - Path=/v1/orders/**
    filters:
      - StripPrefix=0
      - name: RequestRateLimiter
        args:
          redis-rate-limiter.replenishRate: 100
          redis-rate-limiter.burstCapacity: 200
          key-resolver: "#{@tenantKeyResolver}"
      - name: CircuitBreaker
        args: { name: orders, fallbackUri: forward:/fallback }
```

Rules:
- One route per `/v1/<context>/**` mapping to one service.
- Load-balanced via `lb://<service-name>` (Spring Cloud LoadBalancer + k8s service or Eureka).
- Strip/rewrite the prefix at the gateway only if needed; prefer keeping the path stable through.

## 3. Edge authentication

- Gateway validates JWT (Spring Security OAuth2 RS at the gateway).
- On success, gateway sets headers for downstream: `X-Tenant-Id`, `X-User-Id`, `X-Roles`. Services trust these because they came through the gateway boundary (mesh / NetworkPolicy enforces no bypass).
- Tokens are NOT forwarded to internal services unless an explicit route opts in; the gateway is the boundary.
- **Internal service-to-service**: separate, signed service-account tokens (client credentials), validated at each service. Not the user token.

```java
@Bean
SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
    return http
        .authorizeExchange(ex -> ex
            .pathMatchers("/actuator/health/**", "/v1/public/**").permitAll()
            .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .build();
}

@Bean
GlobalFilter identityHeaderFilter() {                       // runs after auth
    return (exchange, chain) -> exchange.getPrincipal()
        .cast(JwtAuthenticationToken.class)
        .map(t -> t.getToken())
        .map(jwt -> exchange.mutate().request(r -> r
            .headers(h -> {
                h.set("X-User-Id",   jwt.getSubject());
                h.set("X-Tenant-Id", jwt.getClaimAsString("tid"));
                h.set("X-Roles",     String.join(",", jwt.getClaimAsStringList("roles")));
            })).build())
        .defaultIfEmpty(exchange)
        .flatMap(chain::filter);
}
```

## 4. CORS — single source of truth

- Gateway returns CORS headers. Services do not.
- Per-tenant origin allowlist read from tenants table or config service.
- `OPTIONS` preflight handled at gateway, never reaches services.

## 5. Rate limiting

| Tier        | Limit                                         | Implementation                                            |
| ----------- | --------------------------------------------- | --------------------------------------------------------- |
| Per-IP      | 200 req/s burst, 50/s sustained               | Spring Cloud Gateway `IpKeyResolver` + Redis              |
| Per-tenant  | 100/s default; tenant-tier overrides          | `tenantKeyResolver` bean; Redis-backed                    |
| Per-route   | Endpoint-specific (e.g., search 10/s)         | Composite key resolver                                    |
| Per-user    | 20/s sustained                                | `UserKeyResolver` from JWT `sub`                          |

- Response on exceeded: `429` + RFC 7807 problem body (delegated to a `RateLimitedException` → gateway error handler) + `Retry-After` header.
- Bucket4j as an alternative when Redis isn't available.

```java
@Bean KeyResolver tenantKeyResolver() {
    return ex -> Mono.justOrEmpty(ex.getRequest().getHeaders().getFirst("X-Tenant-Id"))
        .switchIfEmpty(Mono.just("anonymous"));
}
@Bean KeyResolver userKeyResolver() {
    return ex -> ex.getPrincipal()
        .cast(JwtAuthenticationToken.class)
        .map(t -> t.getToken().getSubject())
        .defaultIfEmpty("anonymous");
}
@Bean KeyResolver ipKeyResolver() {
    return ex -> Mono.just(ex.getRequest().getRemoteAddress().getAddress().getHostAddress());
}
```

## 6. Header sanitization

- Strip caller-supplied `X-Tenant-Id`, `X-User-Id`, `X-Roles` on inbound (only the gateway sets these).
- Strip sensitive backend headers from outbound (no `Server`, no internal stack traces).
- Propagate `traceparent` (auto via OTel), `correlation_id`, `request_id`.

```java
@Bean
GlobalFilter inboundHeaderScrub() {
    var spoofable = Set.of("X-Tenant-Id", "X-User-Id", "X-Roles", "X-Internal");
    return (exchange, chain) -> chain.filter(exchange.mutate()
        .request(r -> r.headers(h -> spoofable.forEach(h::remove)))
        .build());
}
```
Order this filter **before** `identityHeaderFilter` so the gateway-set values are the only ones that reach downstream.

## 7. Canary / weighted routing

```yaml
- id: orders-canary
  uri: lb://orders-svc-v2
  predicates:
    - Path=/v1/orders/**
    - Weight=group=orders, weight=10           # 10% to v2
- id: orders-stable
  uri: lb://orders-svc
  predicates:
    - Path=/v1/orders/**
    - Weight=group=orders, weight=90
```
- Combine with header-based steering (`X-Canary: true` for opt-in) for support-staff dogfooding.
- Always measure: divergence in error rate / latency between weighted variants.

## 8. Request mirroring (traffic shadowing)

- Route 100% to prod, also mirror to a shadow target. Use to test new versions without user impact.
- Mirror response **discarded** at the gateway; never bleeds into prod responses.
- Caveat: mirrored side effects (writes) — only safe for read-only or idempotent endpoints; otherwise use a dedicated shadow service that no-ops writes.

## 9. Egress gateway (outbound)

- Pattern: a *separate* gateway for outbound HTTP — to partners, payment providers, LLM providers.
- Reasons: cost attribution per provider, audit every external call, central allowlist, central retry policy, central credential injection.
- Especially valuable for LLM calls (token spend + audit).
- Implementation: Spring Cloud Gateway in egress mode + Resilience4j + a `egress.allowlist` of permitted hosts.

```yaml
spring.cloud.gateway.routes:
  - id: egress-openai
    uri: https://api.openai.com
    predicates: [ Path=/egress/openai/** ]
    filters:
      - RewritePath=/egress/openai/(?<seg>.*), /$\{seg}
      - name: AddRequestHeader
        args: { name: Authorization, value: "Bearer ${OPENAI_API_KEY}" }
      - name: Retry
        args: { retries: 2, statuses: BAD_GATEWAY, methods: GET }
      - name: CircuitBreaker
        args: { name: openai, fallbackUri: forward:/egress/fallback }
```
- Internal services call `http://egress-gw/egress/<provider>/...` — never the provider directly.
- Tag spans with `egress.provider`, `egress.tenant` for per-tenant cost rollups.

## 10. WebSocket / SSE through the gateway

- Spring Cloud Gateway proxies both. Use `lb:ws://` URIs.
- Sticky session via consistent hashing if the protocol requires it (rare for SSE).
- Timeout config — WebSocket idle timeout in minutes, not seconds.

## 11. Health and metrics

- Gateway exposes its own `/actuator/health` + `prometheus`.
- Per-route metrics: `gateway_requests_seconds{route_id, status}`.
- Alert on per-route 5xx rate, latency p95, and rate-limit-hit rate per tenant (sudden spike = noisy neighbor).

## 12. Multi-tenancy at the gateway

- Tenant resolution at the gateway (`X-Tenant-Id` header, JWT `tid` claim, or subdomain).
- Set `X-Tenant-Id` on the request to downstream (services trust it).
- Rate-limit keys include `tenant_id`.
- Per-tenant feature flags can short-circuit a route (e.g., maintenance mode for tenant X).

## 13. Failure modes

- Gateway down = total outage. Run ≥3 replicas, behind a load balancer. No single-pod gateway.
- Slow downstream → gateway pool exhaustion → propagation of failure. Resilience4j per route + reasonable timeouts.
- Misconfigured route: 502s. Add a "no route matched" log + 404 with Problem body.

```yaml
spring.cloud.gateway.httpclient:
  connect-timeout: 2000          # ms; fail fast on dead pods
  response-timeout: 10s          # cap; per-route overrides allowed
  pool: { type: elastic, max-idle-time: 30s }
resilience4j.circuitbreaker.instances.orders:
  slidingWindowSize: 50
  failureRateThreshold: 50
  waitDurationInOpenState: 10s
```
Tune `response-timeout` per route — a long-running export should not share the default with an OLTP endpoint.

## 14. Configuration management

- Routes/config in version control. Changes via PR + ADR for any new public route.
- Hot reload: Spring Cloud Config + bus, or rolling restart. **Don't** edit production routes via UI.

## 15. Anti-patterns — refuse

- Auth / rate limit in services instead of the gateway
- Multiple gateways serving the same audience (drift surface)
- Forwarding raw JWT into a service (use service-account token internally)
- CORS handled in services instead of gateway
- Service reachable from outside the cluster directly
- Mirrored writes (double-effects)
- Outbound LLM/3rd-party calls bypassing the egress gateway
- Per-route rate limits hardcoded; should be config

## 16. Pre-merge checklist

- [ ] New service registered as a gateway route
- [ ] Auth enforced at gateway, not duplicated downstream
- [ ] Rate limits set per tenant and per route
- [ ] CORS allowlist updated
- [ ] Trace + correlation headers propagating
- [ ] Outbound (egress) calls routed through egress gateway

## 17. Reference

- Spring Cloud Gateway reference — https://docs.spring.io/spring-cloud-gateway/reference/
- `.claude/skills/lib/jabrena/304-frameworks-spring-boot-security/references/304-frameworks-spring-boot-security.md` — security at the edge
- `java-security` framework skill (sibling)
- `java-multi-tenancy` framework skill (sibling) — for `X-Tenant-Id` propagation contract
