---
name: java-principles
description: Use when designing any new service, module, or significant feature — covers the 12-factor app rules mapped to Spring Boot, SOLID with concrete Spring examples, and the core microservices principles (bounded context, DB-per-service, idempotency, contract-first).
---

# Design Principles — 12-Factor, SOLID, Microservices Core

## 1. 12-Factor App (mapped to Spring Boot)

| # | Factor              | Implementation                                                                          |
|---|---------------------|-----------------------------------------------------------------------------------------|
| 1 | **Codebase**        | One repo per service, or mono-repo with strict module boundaries. Tracked in Git.       |
| 2 | **Dependencies**    | Declared in `build.gradle.kts` via version catalog. No system-wide JARs.                |
| 3 | **Config**          | Env vars via `@ConfigurationProperties`. Profiles per env. Secrets via Vault / k8s Secrets, **never** committed. |
| 4 | **Backing services**| DB, Kafka, Redis treated as attached resources. Switch by URL change.                   |
| 5 | **Build/release/run** | Image built once (`bootBuildImage`), promoted across envs. Release = image + config.   |
| 6 | **Processes**       | Stateless. Session in Postgres/Redis. No file-system state.                             |
| 7 | **Port binding**    | Embedded Tomcat. Service self-contained.                                                |
| 8 | **Concurrency**     | Horizontal scale via replicas. Virtual threads internally.                              |
| 9 | **Disposability**   | Fast boot (<10s), `server.shutdown=graceful`, SIGTERM handling.                         |
| 10| **Dev/prod parity** | Testcontainers in tests = real Postgres + Kafka. **No H2.**                             |
| 11| **Logs**            | Structured JSON to stdout. Aggregated by platform (Loki/ELK).                           |
| 12| **Admin processes** | One-off jobs (migrations, backfills) = same image, different command or Spring profile. |

## 2. SOLID — with Spring examples

### S — Single Responsibility
A class has one reason to change.
- Controller: HTTP shape only.
- Use-case (application service): orchestrates domain + repos, owns `@Transactional`.
- Repository: persistence only.
- Domain entity / VO: business invariants only.

**Smell:** a `UserService` that does auth + email + audit + persistence. Split it.

### O — Open/Closed
Open for extension, closed for modification.

```java
// New shipping method = add a bean, don't edit the dispatcher.
public interface ShippingStrategy {
    boolean supports(Order o);
    Quote quote(Order o);
}

@Service
class ShippingDispatcher {
    private final List<ShippingStrategy> strategies;  // Spring injects all beans
    public Quote quoteFor(Order o) {
        return strategies.stream().filter(s -> s.supports(o)).findFirst()
            .orElseThrow().quote(o);
    }
}
```

### L — Liskov Substitution
Subtypes honor the contract. Don't override `save()` to silently skip writes. Don't tighten preconditions.

In practice: **prefer composition over inheritance**. Inheritance is for "is-a-kind-of"; everything else uses interfaces + delegation.

### I — Interface Segregation
Small, role-based interfaces.

```java
// Bad: one fat port
interface UserRepository { save(); find(); delete(); export(); import(); audit(); ... }

// Good
interface UserReader { Optional<User> findById(UserId id); }
interface UserWriter { User save(User u); }
interface UserAuditQuery { List<AuditRow> auditFor(UserId id); }
```

### D — Dependency Inversion
Depend on abstractions. Domain layer depends on nothing framework-specific.

```
domain/        ←  pure Java, no Spring, no JPA
application/   →  depends on domain
infrastructure/→  implements ports from domain/application using Spring/JPA
api/           →  depends on application
```

Wire concretions in `@Configuration` classes.

## 3. Microservices Core Principles

### Bounded Context
- One service = one DDD bounded context. Service name = context name (e.g. `orders`, `billing`, `identity`).
- No cross-context entity sharing. If `Customer` exists in `identity` and `orders`, they are two different types.

### Database per Service
- Each service owns its schema(s). **No** cross-service SQL, ever.
- Need another service's data? Call its API, subscribe to its events, or materialize a local read model.

### Contract-First
- OpenAPI spec is the source of truth for REST. Avro schema for events. Generate stubs.
- Breaking change = new version (`/v2/...` or `OrderCreated.v2`). Old version supported until consumers migrate.

### Async by Default for Inter-Service
- Synchronous REST only when the caller needs the answer now (queries, user-facing reads).
- Mutations that other services care about → publish an event.

### Idempotency Everywhere
- Mutating REST endpoints accept `Idempotency-Key` header; store `(key, response)` for replay.
- Kafka consumers dedupe by `event_id` in a `processed_events` table.

### Tenant-Aware
- Every request carries tenant context (header → JWT → resolved).
- Every Kafka message carries `tenant_id` in headers.
- Every DB connection enters with the tenant's `search_path` set.

### Reliability Defaults
- Every outbound call: timeout + retry-with-jitter + circuit breaker (Resilience4j).
- Every consumer: DLT + max-retries + alerting.
- Every endpoint: rate limit at gateway.

### Observable by Default
- Trace IDs propagated across HTTP and Kafka headers.
- One business event = one structured log line at INFO, with `tenant_id`, `correlation_id`, `event_type`.
- Domain metrics via Micrometer (`orders.placed.total`, `payments.failed.total`).

## 4. Separation of Concerns

### What SoC means in this framework

Each module/class/method/file owns **one concern**. The test: can you describe what it does in one sentence using "and" zero times?

SoC is what makes hexagonal architecture (`java-architecture`) **legible**, what makes tests fast (`java-testing`), and what makes the framework's other rules enforceable. It's the meta-rule.

### Five concerns, five layers — sharp boundaries

| Concern | Layer | What it does | What it does NOT do |
| --- | --- | --- | --- |
| HTTP shape | `api/` | Receive/return JSON, map DTO↔domain | Persist; orchestrate; emit events |
| Use-case orchestration | `application/` | Compose domain calls; own `@Transactional`; publish via outbox | Validate JSON; serialize; deal with HTTP status codes |
| Domain logic | `domain/` | Invariants on entities/VOs; pure Java | Know Spring, JPA, Kafka exist |
| Persistence / messaging / external HTTP | `infrastructure/` | Talk to DB/Kafka/external APIs; translate to/from domain types | Make business decisions |
| Wiring | `config/` | `@Configuration`, beans, security, observability | Hold any business logic |

If a class crosses these lines, split it. ArchUnit enforces (see `java-architecture` §6).

### Common violations and how to refactor

**Controller doing too much**
```java
// WRONG — controller validates business rules, persists, emits, formats
@PostMapping
ResponseEntity<?> place(@RequestBody PlaceOrderRequest r) {
    if (r.lines().isEmpty()) return ResponseEntity.badRequest()...;
    var order = new Order(...);
    orderRepo.save(order);
    kafkaTemplate.send(...);
    return ResponseEntity.ok(order);
}
```
Fix: validation via `@Valid`, business rules in domain, persist+event in use-case, controller maps DTO + status only.

**God-service**
```java
@Service class UserService {
    /* methods: createUser, sendEmail, generateInvoice, scheduleReminder, exportToCsv */
}
```
Five concerns → five services (or a domain service + four adapters). Each <200 lines, each with one reason to change.

**Anemic domain + fat service**
A `User` with only getters/setters + a `UserService` with all the rules → put invariants on `User` itself; `UserService` shrinks to orchestration.

### Concern boundaries enforced — the rules

- **One controller per resource**. Not one controller for "API". Splits keep test scope tight.
- **One use-case per public action.** `PlaceOrderUseCase`, `CancelOrderUseCase` — not `OrderUseCases.placeOrder()` and `OrderUseCases.cancelOrder()` lumped together.
- **One repository per aggregate root.** Not a generic `BaseRepository<Object>`.
- **One adapter per outbound port.** `StripePaymentGatewayAdapter`, not `ExternalServicesAdapter`.
- **One Flyway migration per concern.** Adding a table + adding a column to another = two migrations (see `java-migrations` §2.4).
- **One commit per coherent change.** See `java-git-workflow` §3 — atomic commits with a single `Intent:` block.

### Frontend SoC mirror

Frontend services in the same org follow the analogous split:
- View / presentation
- State / hooks / view-model
- Use-case (calling backend APIs)
- API client (translation layer to backend DTOs)
- Domain types (TypeScript types matching ours)

The principle is identical. Tests align: unit tests on view-models and use-cases; integration tests on API clients; E2E across both stacks.

### The 1-sentence test (apply on every PR)

For each class/file in the diff, finish: "This class ____."

If you used "and", you have a SoC problem. Split before merging.

### Where SoC bends — and why

- DTOs may live close to controllers (`api/dto/`) even though they're "data shape", because their concern is genuinely "HTTP shape" not "domain".
- Mappers are a tolerated cross-cutter — they exist precisely to translate concerns; keeping them tiny is the discipline.
- `config/` knows everything; that's its job.

### Anti-patterns — refuse

- Util classes with static business methods (`OrderUtils`)
- `Manager` / `Helper` suffix without a clear single concern
- One controller that hits five repositories
- A service named after a technical thing (`KafkaService`, `JpaService`) instead of a business thing
- Domain entity with `@Entity` annotations (mixes domain and persistence concerns)
- A use-case that calls another use-case (sign of missing domain service)
- "We'll separate this later" — refactoring later costs 10× more

## 5. The Test Before Shipping

Ask, for each module/change:
1. Could this class change for two unrelated reasons? → split it (SRP).
2. Does adding the next variant require editing existing code? → invert with strategy (OCP).
3. Is the domain layer importing `org.springframework` or `jakarta.persistence`? → fix it (DIP).
4. Is the request path stateful, file-bound, or assuming a specific instance? → fix it (12-factor 6/9).
5. Does this mutation publish an event without an outbox? → fix it (microservices reliability).
