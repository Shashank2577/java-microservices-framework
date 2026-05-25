---
name: java-testing
description: Use whenever writing tests, deciding test scope (unit vs integration vs contract), or setting up Testcontainers, ArchUnit, or contract tests. Covers the test pyramid for this stack, mandatory Testcontainers for DB/Kafka, ArchUnit rules, contract tests (Spring Cloud Contract / Pact), mutation testing, and coverage targets.
---

# Testing Strategy

## 1. The Pyramid (this stack)

```
                ┌─────────────────────┐
                │   E2E (very few)    │   smoke tests in a deployed env
                ├─────────────────────┤
                │ Contract tests      │   provider + consumer (Pact / Spring Cloud Contract)
                ├─────────────────────┤
                │ Integration         │   @SpringBootTest + Testcontainers (Postgres, Kafka)
                ├─────────────────────┤
                │ Architecture        │   ArchUnit — runs every build
                ├─────────────────────┤
                │ Unit (the bulk)     │   plain JUnit, no Spring, fast
                └─────────────────────┘
```

Aim for the bulk in unit tests (domain logic), a strong integration layer (use-case + repo + outbox + consumer), and contract tests at each service boundary.

## 2. Unit Tests — Domain & Use-Cases

- No Spring context, no DB, no mocks-of-frameworks.
- Mock **ports** (outbound interfaces). Use real domain objects.
- JUnit 5 + AssertJ. Mockito where mocking is unavoidable.

```java
class PlaceOrderTest {
    OrderRepository orders = mock();
    InventoryGateway inv  = mock();
    DomainEventPublisher events = mock();
    PlaceOrder useCase = new PlaceOrder(orders, inv, events);

    @Test void places_order_and_publishes_event() {
        var cmd = new PlaceOrderCommand(tenant, customer, List.of(line));
        when(inv.reserve(any())).thenReturn(Reservation.ok());

        var id = useCase.place(cmd);

        assertThat(id).isNotNull();
        verify(orders).save(any(Order.class));
        verify(events).publish(any(OrderPlaced.class));
    }
}
```

## 3. Integration Tests — Testcontainers Only

**No H2. Ever.** Tests run against the same Postgres major version as production.

```java
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class OrderApiIT {
    @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mvc;

    @Test void places_order_and_emits_event() throws Exception {
        mvc.perform(post("/v1/orders")
                .header("X-Tenant-Id", "acme")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(APPLICATION_JSON)
                .content("""
                  { "customerId": "...", "lines": [ { "productId":"...", "qty": 2 } ] }
                """))
            .andExpect(status().isCreated());
        // assert outbox row written, kafka record produced, etc.
    }
}
```

Reuse containers across tests with `@Container` on `static` fields + Singleton container pattern when build times suffer.

## 4. Slice Tests (use sparingly)

- `@DataJpaTest` — replaced by full Testcontainers test in this stack to avoid H2 surprises.
- `@WebMvcTest` — for pure controller logic with mocked use-cases; OK when no DB access is involved.

## 5. Architecture Tests — ArchUnit

Always present, always green. See `java-architecture` skill for the rule set. They live alongside other tests; CI fails on violation.

## 6. Contract Tests

For each service boundary (REST or events), there's a producer-side and a consumer-side contract. Default: **Spring Cloud Contract (provider-driven)** for in-org consumers. **Pact (consumer-driven)** for external partners.

### 6.1 REST — Spring Cloud Contract (provider-driven)

Producer writes contracts in `src/test/resources/contracts/<scenario>.yml`. The Spring Cloud Contract Verifier generates a JUnit test that fails the producer build if the real controller drifts from the contract, and publishes a **stub jar** to internal Maven/Nexus.

```yaml
# src/test/resources/contracts/orders/get_order_found.yml
description: "GET /v1/orders/{id} returns the order for tenant acme"
request:
  method: GET
  url: /v1/orders/9b1f...e7
  headers:
    X-Tenant-Id: "acme"
    Accept: "application/json"
response:
  status: 200
  headers:
    Content-Type: "application/json"
  body:
    id: "9b1f...e7"
    status: "PLACED"
    customerId: "c-42"
    totalCents: 1999
  matchers:
    body:
      - path: $.id
        type: by_regex
        value: "[0-9a-f-]{36}"
```

Add a sibling `get_order_not_found.yml` returning `404` with `X-Tenant-Id: acme` and a different id. Producer test class extends a generated base; consumer pulls stubs via:

```java
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.example:orders-svc:+:stubs:8090",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL)
class OrdersClientContractTest { /* assert client maps 404 → OrderNotFound */ }
```

### 6.2 Event Contracts — Spring Cloud Contract Messaging (Kafka)

Producer contract for `orders.order.placed.v1`:

```yaml
# src/test/resources/contracts/events/order_placed.yml
description: "OrderPlaced is emitted when an order is placed"
label: "order_placed_event"
input:
  triggeredBy: "placeOrder()"
outputMessage:
  sentTo: "orders.order.placed.v1"
  headers:
    X-Tenant-Id: "acme"
    contentType: "application/json"
  body:
    eventId: "anyUuid()"
    occurredAt: "anyIso8601WithOffset()"
    orderId: "9b1f...e7"
    customerId: "c-42"
    totalCents: 1999
```

Generated test invokes `placeOrder()` and asserts the message landed on the topic with matching shape + headers. Consumer-side uses the stub runner in **messaging mode** to replay the contracted event into its listener.

### 6.3 Pact — Consumer-Driven (external partners)

Use when consumers must drive the shape (mobile apps, third parties). Pact files land in a Pact Broker; provider build runs `pact:verify`. We only reach for Pact when teams genuinely disagree on contract direction — otherwise Spring Cloud Contract wins on Spring integration and Kafka support.

### 6.4 CI Wiring

- Provider build: contract tests run in `./gradlew contractTest`; on green, `publishStubsPublication` ships the stub jar.
- Consumer build: pulls **latest released** stubs by default; a nightly job pulls `+` (LATEST) to catch upcoming breakage early.
- A consumer change that requires a producer change blocks merge until the provider releases the new contract.

### 6.5 Tenant-Aware Contracts

Every REST contract MUST include `X-Tenant-Id` in request headers; every Kafka contract MUST include `X-Tenant-Id` in message headers. Contracts without tenant headers fail review.

## 7. Mutation Testing (PIT)

```kotlin
tasks.named("test") { finalizedBy("pitest") }
plugins { id("info.solidsoft.pitest") }
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("com.example.<service>.application.*", "com.example.<service>.domain.*"))
    mutators.set(listOf("STRONGER"))
    timestampedReports.set(false)
    mutationThreshold.set(70)
}
```
Apply to **domain + application** packages (the layers worth mutating). Skip for boilerplate adapters.

## 8. Coverage Targets

- Line coverage ≥ **80%** on changed code (Jacoco).
- Branch coverage ≥ **70%** on changed code.
- Mutation score ≥ **70%** on domain + application packages.
- No coverage targets for `infrastructure.persistence.jpa` mappers, `config`, generated code.

## 9. Test Naming

- Class: `<Subject>Test` (unit), `<Subject>IT` (integration).
- Method: `behavior_when_condition` or `does_X_when_Y` — descriptive sentences. Avoid `test1`, `testFoo`.

```java
@Test void places_order_when_inventory_reservation_succeeds() { ... }
@Test void rejects_order_with_no_lines() { ... }
@Test void publishes_OrderPlaced_event_in_same_transaction_as_save() { ... }
```

## 10. Patterns

### AAA (Arrange-Act-Assert)
Use blank lines to separate sections.

### Test Data Builders
For complex aggregates, hand-written builders or `OrderTestData.aPlacedOrder().withTotal(Money.usd(100)).build()`. Keep them in `src/test/java`.

### `@ParameterizedTest` for edge tables
```java
@ParameterizedTest
@CsvSource({"DRAFT, PLACED, true", "PLACED, PLACED, false", "PAID, PLACED, false"})
void place_transition(OrderStatus from, OrderStatus expected, boolean allowed) { ... }
```

### Time Control
Inject a `Clock` bean. In tests, use `Clock.fixed(...)`. Never call `Instant.now()` directly in domain.

### Random Data
Avoid random unless reproducible (`Random` with a fixed seed, or property-based tests with jqwik).

## 11. CI Test Jobs

1. `./gradlew test` — unit + slice tests.
2. `./gradlew integrationTest` — Testcontainers IT (separate source set, longer timeout).
3. `./gradlew archTest` — ArchUnit (cheap, runs in `test`).
4. `./gradlew contractTest` — provider + consumer.
5. `./gradlew pitest` — mutation, weekly or on label.

Fail the build on any failure. No "flaky" allow-listing.

## 12. Local Dev Loop

The local loop must look like prod, not like a test. No H2, no in-memory Kafka, no real shared dev DB.

### 12.1 `docker-compose.yml` at repo root

Runs the production-shaped dependencies. Sketch:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment: [POSTGRES_USER=app, POSTGRES_PASSWORD=app, POSTGRES_DB=app]
    ports: ["5432:5432"]
  kafka:
    image: confluentinc/cp-kafka:7.6.0   # KRaft mode, no zookeeper
    ports: ["9092:9092"]
  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.0
    ports: ["8081:8081"]
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    volumes: ["./local/keycloak:/opt/keycloak/data/import"]
    ports: ["8088:8080"]
```

### 12.2 Spring profile `local`

`application-local.yml` points at compose endpoints, enables verbose SQL (`org.hibernate.SQL=DEBUG`), relaxes CORS, and trusts the mock IdP's signing key.

### 12.3 Testcontainers Reuse

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

```java
static final PostgreSQLContainer<?> PG =
    new PostgreSQLContainer<>("postgres:16-alpine")
        .withReuse(true);
```

Containers survive between test runs in the same dev session — iteration goes from ~25s to ~3s. **Reuse disables Ryuk cleanup**; document the periodic cleanup: `docker rm -f $(docker ps -a -q --filter label=org.testcontainers=true)`.

### 12.4 Singleton Container Base Class

Lazily start once per JVM; share across every IT class.

```java
public abstract class IntegrationTestBase {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0")).withReuse(true);
    static { PG.start(); KAFKA.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
```

### 12.5 Test Profile & Migrations

`@ActiveProfiles("test")` + `application-test.yml`:

```yaml
spring:
  flyway:
    locations: classpath:db/migration/public,classpath:db/migration/tenant
```

Mirrors production migration order so tests can't pass while prod migrations fail.

### 12.6 Mock IdP for Local

Either a Keycloak Testcontainer with a pre-imported realm, or a minimal in-process JWT issuer that signs with a test private key. Resource server trusts:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: classpath:test-pub.pem
```

Never point local dev at the real IdP — token leaks and rate limits both bite.

### 12.7 Hot Reload

Spring DevTools is enabled in `local` profile only — never test, never prod. JRebel or `-XX:+EnableDynamicAgentLoading` only when restart times genuinely hurt; usually not worth it.

### 12.8 Entry Point

`./gradlew bootRun -Plocal` is the canonical way to start the service. IDE Run configs drift between developers; the Gradle task doesn't.

### 12.9 devcontainer.json

For VSCode users, `.devcontainer/devcontainer.json` provisions JDK 21, Gradle, Docker-in-Docker, and seeds `~/.testcontainers.properties` so reuse works on first clone.

### 12.10 Local Dev Anti-patterns — Refuse

- H2 / HSQLDB for local dev. Compose Postgres.
- Hardcoded `localhost:5432` in test code. Use `@DynamicPropertySource` from Testcontainers.
- Real IdP for local dev. Keycloak container or in-process mock.
- `bootRun` against a shared dev DB. One developer's migration breaks the rest.

## 13. Anti-patterns — Refuse

- H2 / HSQLDB for integration tests. Postgres only via Testcontainers.
- Mocking `JpaRepository` or `KafkaTemplate` in an integration test (defeats the point).
- Testing private methods. Test through the public boundary.
- A unit test that spins up Spring context.
- `Thread.sleep()` in tests. Use `Awaitility.await().atMost(...).until(...)`.
- Hardcoded times / dates without a fixed `Clock`.
- Asserting log output as the only assertion (use the actual return value or side-effect).
- Mass-disabled tests with `@Disabled` and no linked issue.

## References

- `.claude/skills/lib/jabrena/132-java-testing-integration-testing/references/132-java-testing-integration-testing.md` — integration test patterns and Testcontainers wiring.
- `.claude/skills/lib/jabrena/133-java-testing-acceptance-tests/references/133-java-testing-acceptance-tests.md` — acceptance / black-box test guidance.
- `.claude/skills/lib/jabrena/702-technologies-wiremock/references/702-technologies-wiremock.md` — WireMock for stubbing outbound HTTP collaborators in IT and contract scenarios.
- `.claude/skills/lib/jabrena/322-frameworks-spring-boot-testing-integration-tests/references/322-frameworks-spring-boot-testing-integration-tests.md` — Spring Boot integration test slice/full-context tradeoffs.
