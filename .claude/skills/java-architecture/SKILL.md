---
name: java-architecture
description: Use when creating a new service module, adding a package, or making a decision that crosses layers (controller calling repo directly, domain importing Spring, etc.). Covers hexagonal/ports-and-adapters layout, package architecture rules, and ArchUnit enforcement.
---

# Architecture — Hexagonal + Layered Packages

## 1. The Layering

```
                    ┌──────────────────┐
                    │       api        │  HTTP, DTOs, mappers
                    └────────┬─────────┘
                             ↓ uses
                    ┌──────────────────┐
                    │   application    │  use-cases, @Transactional
                    │                  │  defines PORTS (interfaces)
                    └────────┬─────────┘
                             ↓ uses
                    ┌──────────────────┐
                    │      domain      │  entities, VOs, domain services
                    │                  │  PURE JAVA. No Spring. No JPA.
                    │                  │  emits domain events ──────────┐
                    └──────────────────┘                                │
                             ↑ implements ports                         │
                                                                        ↓
                                                                  (outbox / bus)
                    ┌──────────────────┐
                    │  infrastructure  │  JPA repos, Kafka, HTTP clients
                    └──────────────────┘
                             ↑ wires
                    ┌──────────────────┐
                    │      config      │  @Configuration, beans
                    └──────────────────┘
```

**Dependency rule:** arrows above. `api → application → domain`. `infrastructure → application/domain`. `config → everything`. Never the reverse.

## 2. Package Layout (per service)

```
com.<org>.<service>
├── api
│   ├── rest                 # @RestController, @ExceptionHandler
│   ├── dto                  # request/response records
│   └── mapper               # DTO ↔ domain
├── application
│   ├── usecase              # one class per use-case (PlaceOrder, CancelOrder)
│   ├── port
│   │   ├── in               # use-case interfaces (driven by api)
│   │   └── out              # gateway interfaces (implemented by infra)
│   └── service              # domain orchestration helpers
├── domain
│   ├── model                # entities, aggregates, value objects, domain events
│   ├── service              # pure domain logic that doesn't belong on entities
│   └── error                # domain exceptions / sealed Result types
├── infrastructure
│   ├── persistence
│   │   ├── jpa              # JPA entities, repositories, mappers to domain
│   │   └── jooq             # jOOQ queries when JPA isn't enough
│   ├── messaging
│   │   ├── kafka            # producers, consumers, outbox
│   │   └── event            # event payload schemas (Avro generated)
│   ├── http                 # outbound REST clients (Feign or RestClient)
│   └── tenant               # tenant context, multi-tenant datasource
└── config                   # @Configuration, security, observability
```

### Naming
- Use-cases: imperative (`PlaceOrderUseCase`, `CancelOrderUseCase`).
- Ports `in`: `<UseCaseName>` interface + `<UseCaseName>Impl` (or `<...>Service`).
- Ports `out`: `<Capability>Gateway` (`PaymentGateway`, `InventoryGateway`) or `<Aggregate>Repository`.
- JPA entities: `<Aggregate>JpaEntity` — kept distinct from domain `<Aggregate>`.

## 3. Domain Layer — Strict Rules

The domain package is the heart. It must:
- Have **zero** imports from `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`, or any infrastructure lib.
- Use only `java.*`, the `domain-primitives` building block, and other domain types.
- Express invariants in constructors and methods, not in services that wrap entities.

```java
// domain/model/Order.java — pure, no annotations
public final class Order {
    private final OrderId id;
    private final TenantId tenant;
    private final CustomerId customer;
    private final List<OrderLine> lines;
    private OrderStatus status;
    private Money total;
    private long version;

    public Order(OrderId id, TenantId tenant, CustomerId customer) {
        this.id = Objects.requireNonNull(id);
        this.tenant = Objects.requireNonNull(tenant);
        this.customer = Objects.requireNonNull(customer);
        this.lines = new ArrayList<>();
        this.status = OrderStatus.DRAFT;
        this.total = Money.ZERO;
    }

    public void addLine(OrderLine line) {
        if (status != OrderStatus.DRAFT) throw new IllegalStateException("cannot modify after " + status);
        lines.add(line);
        total = total.add(line.subtotal());
    }

    public DomainEvent place() {
        if (lines.isEmpty()) throw new IllegalStateException("empty order");
        this.status = OrderStatus.PLACED;
        return new OrderPlaced(id, tenant, customer, total, Instant.now());
    }
    // ... getters
}
```

JPA entity (in `infrastructure/persistence/jpa/`) is a separate class. A mapper translates between them. Yes, this is extra code; it pays for itself the day you swap persistence or test the domain in isolation.

## 4. Use-Case Pattern (Application Layer)

One use-case per public action. Owns the transaction boundary, calls ports, returns DTOs.

```java
// application/port/in/PlaceOrderUseCase.java
public interface PlaceOrderUseCase {
    OrderId place(PlaceOrderCommand cmd);
}

// application/usecase/PlaceOrder.java
@Service
@RequiredArgsConstructor
class PlaceOrder implements PlaceOrderUseCase {
    private final OrderRepository orders;          // port out
    private final InventoryGateway inventory;      // port out
    private final DomainEventPublisher events;     // port out (outbox-backed)

    @Override
    @Transactional
    public OrderId place(PlaceOrderCommand cmd) {
        var order = new Order(OrderId.newId(), cmd.tenant(), cmd.customer());
        cmd.lines().forEach(l -> order.addLine(toDomain(l)));
        inventory.reserve(order);                  // throws on failure → tx rollback
        var event = order.place();
        orders.save(order);
        events.publish(event);                     // writes to outbox in same TX
        return order.id();
    }
}
```

## 5. Ports & Adapters

Domain/Application defines the interface (port). Infrastructure provides the implementation (adapter).

```java
// application/port/out/OrderRepository.java  (port — pure)
public interface OrderRepository {
    Optional<Order> findById(TenantId t, OrderId id);
    void save(Order o);
}

// infrastructure/persistence/jpa/OrderJpaAdapter.java (adapter)
@Repository
@RequiredArgsConstructor
class OrderJpaAdapter implements OrderRepository {
    private final OrderJpaRepository jpa;
    private final OrderJpaMapper mapper;

    public Optional<Order> findById(TenantId t, OrderId id) {
        return jpa.findByTenantIdAndId(t.value(), id.value()).map(mapper::toDomain);
    }
    public void save(Order o) {
        jpa.save(mapper.toJpa(o));
    }
}
```

## 6. ArchUnit — Enforce the Architecture in Tests

Add to `platform/building-blocks/test-support`:
```java
@AnalyzeClasses(packages = "com.example", importOptions = DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest static final ArchRule domain_has_no_framework_deps =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..",
                "com.fasterxml.jackson..", "..infrastructure..");

    @ArchTest static final ArchRule application_only_depends_on_domain =
        classes().that().resideInAPackage("..application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "..application..", "..domain..", "java..", "org.slf4j..",
                "org.springframework.stereotype..", "org.springframework.transaction..");

    @ArchTest static final ArchRule controllers_use_use_cases_only =
        classes().that().resideInAPackage("..api.rest..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "..api..", "..application.port.in..", "java..", "org.springframework..");

    @ArchTest static final ArchRule no_jpa_entity_leaks_out_of_infra =
        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().resideInAPackage("..infrastructure.persistence.jpa..");

    @ArchTest static final ArchRule layered =
        layeredArchitecture().consideringAllDependencies()
            .layer("api").definedBy("..api..")
            .layer("application").definedBy("..application..")
            .layer("domain").definedBy("..domain..")
            .layer("infrastructure").definedBy("..infrastructure..")
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .whereLayer("application").mayOnlyBeAccessedByLayers("api", "infrastructure", "config")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure")
            .whereLayer("infrastructure").mayOnlyBeAccessedByLayers("config");
}
```

Every service includes these tests. CI fails on violation.

## 7. DDD Tactical Patterns

Hexagonal layout tells you *where* code lives. DDD tactical patterns tell you *what shape* it takes inside the domain. Get these wrong and your "domain" becomes a CRUD anemic blob with a fancier package name.

### 7.1 Aggregate

An aggregate is a **consistency boundary**: a cluster of objects treated as one unit for data changes. The root is the only entry point.

| Rule | Why |
| ---- | --- |
| One aggregate mutated per transaction | Cross-aggregate consistency is eventual — use events, not joins |
| Root holds all invariants | No half-valid state reachable from outside |
| Children reachable **only** through root | No `orderLineRepository.save(line)` — go through `Order` |
| Reference other aggregates **by ID only** | `CustomerId customer`, never `Customer customer` |
| Load + save the whole aggregate | The repo deals in roots, not parts |

Why ID-only references? In microservices the other aggregate may live in another service, another DB, another bounded context. An object reference is a lie waiting to NPE.

```java
// domain/model/Order.java — aggregate root
public final class Order {
    private final OrderId id;
    private final TenantId tenant;
    private final CustomerId customer;          // by ID, not Customer
    private final List<OrderLine> lines;        // child entity, only mutable via root
    private OrderStatus status;
    private Money total;
    private long version;

    public void addLine(OrderLine line) {       // invariant lives here
        if (status != OrderStatus.DRAFT) throw new IllegalStateException("locked: " + status);
        lines.add(line);
        total = total.add(line.subtotal());
    }
    // no public setter for lines, no leaking the internal list
    public List<OrderLine> lines() { return List.copyOf(lines); }
}
```

### 7.2 Entity vs Value Object

| | Entity | Value Object |
| --- | ------ | ------------ |
| Identity | has an `id`, equality by id | no id, equality by all fields |
| Mutability | mutable through methods | **immutable** (record) |
| Lifecycle | created, modified, deleted | created and replaced |
| Validation | invariants enforced in methods | invariants in **constructor** |
| Examples | `Order`, `Customer`, `OrderLine` | `Money`, `Address`, `EmailAddress`, `OrderId` |

VOs are records with validation in the canonical constructor. Throw `IllegalArgumentException` on bad input — fail fast, fail loud.

```java
public record Money(BigDecimal amount, Currency currency) {
    public static final Money ZERO = new Money(BigDecimal.ZERO, Currency.getInstance("USD"));
    public Money {
        if (amount == null || currency == null) throw new IllegalArgumentException("null money");
        if (amount.scale() > currency.getDefaultFractionDigits())
            throw new IllegalArgumentException("scale exceeds currency");
    }
    public Money add(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch");
        return new Money(amount.add(other.amount), currency);
    }
}

public record EmailAddress(String value) {
    private static final Pattern RFC = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public EmailAddress {
        if (value == null || !RFC.matcher(value).matches())
            throw new IllegalArgumentException("invalid email: " + value);
    }
}
```

### 7.3 Domain Events

Aggregates emit events when state changes. Records, immutable, **named past-tense** (`OrderPlaced`, not `PlaceOrder`). They are facts, not commands.

```java
// domain/model/event/OrderPlaced.java
public record OrderPlaced(
    OrderId orderId,
    TenantId tenant,
    CustomerId customer,
    Money total,
    Instant occurredAt
) implements DomainEvent {}
```

The aggregate's mutating method returns the event (or appends to an internal list); the use-case publishes it. Publication is via **outbox in the same transaction** as the aggregate save — never a direct broker call inside `@Transactional`. See §4 for the wiring.

```java
public DomainEvent place() {
    if (lines.isEmpty()) throw new IllegalStateException("empty order");
    this.status = OrderStatus.PLACED;
    return new OrderPlaced(id, tenant, customer, total, Instant.now());
}
```

### 7.4 Domain Service vs Application Service

| | Domain Service | Application Service (Use-Case) |
| --- | -------------- | ------------------------------ |
| Holds | Business rules that don't fit on one entity (pricing across aggregates, tax, fx) | Orchestration of a use-case |
| Knows about | Domain only | Domain + ports |
| Transactions | None | Owns `@Transactional` |
| Spring | None — pure Java | `@Service` + ports injected |
| Example | `PricingPolicy.priceFor(order, customer, promotions)` | `PlaceOrder.place(cmd)` |

If logic belongs naturally on `Order` → put it on `Order`. If it spans `Order` + `Customer` + `PromotionCatalog` → domain service. If it talks to a port (DB, HTTP, Kafka) → application service. **Domain services do not call ports.**

### 7.5 Repository (DDD-style)

A repository is a collection-of-aggregates abstraction, **not** a CRUD bag. Interface in `application/port/out` (or `domain` if you prefer); implementation in `infrastructure/persistence`. Method names reflect domain language.

```java
public interface OrderRepository {
    Optional<Order> byId(TenantId t, OrderId id);
    List<Order> byCustomerInTenant(TenantId t, CustomerId c);
    void save(Order order);                     // takes the aggregate root
    // NO findAll(). NO findByStatusAndCreatedAtBetween(...). NO Pageable<OrderLine>.
}
```

Rules: returns aggregates (or `Optional<Aggregate>` / `List<Aggregate>`), never JPA entities, never DTOs, never the child entity alone. Read-heavy queries that don't fit the aggregate shape belong in a **separate query service** (CQRS-lite), not jammed into the repository.

### 7.6 Anti-Corruption Layer (ACL)

When integrating with an external system, a legacy service, or a vendor API, **translate at the boundary**. The ACL lives in `infrastructure/` as an adapter; the foreign data model never enters `domain/`.

```java
// infrastructure/http/legacy/LegacyBillingAclAdapter.java
@Component
class LegacyBillingAclAdapter implements BillingGateway {
    private final LegacySoapClient legacy;       // foreign: SOAP, ALL_CAPS fields, ints-as-cents

    public Invoice issueInvoice(Order order) {
        var foreign = legacy.CREATE_BILL(toLegacyDto(order));   // translate out
        return toDomain(foreign);                                // translate in
    }
}
```

If you find yourself importing a vendor's `BillRequestV2` into a use-case, you've skipped the ACL. The cost shows up the day the vendor breaks the contract.

### 7.7 Bounded Context

One service = one bounded context = **one aggregate family**. `Order` in *order-service* and `Order` in *fulfillment-service* are different types — same word, different meaning. Do **not** extract a `shared-domain` library across services; that's a bounded context smashed flat. Share contracts via events (Avro schemas in `infrastructure/messaging/event`), not Java types.

### Smells you'd see if this is wrong

- `orderRepository.save(line)` — child saved directly, root bypassed.
- `Order` has a `Customer customer` field — cross-aggregate object reference.
- `Money` is a class with setters — VO mutability leak.
- An event named `PlaceOrder` or `OrderPlace` — that's a command, not an event.
- `PricingService` injected with `OrderJpaRepository` — domain service reaching for a port.
- `findAll()` on a repository called from a controller — the aggregate boundary just collapsed.

## 8. The Smell List

If you see any of these, fix before merge:
- A `@RestController` calling a `JpaRepository` directly.
- A `domain/` class with a `@Component`, `@Entity`, or `import org.springframework`.
- A use-case returning a JPA entity to the controller.
- Business logic in a JPA entity's getter/setter.
- A `*Util` class with static business methods — promote to a domain service.
- A "shared-domain" library imported by multiple services — break the bounded context.

## Reference

- `.claude/skills/lib/jabrena/121-java-object-oriented-design/references/121-java-object-oriented-design.md` — OO design depth (composition, immutability, code smells).
- `.claude/skills/lib/jabrena/122-java-type-design/references/122-java-type-design.md` — type-design depth (records, sealed types, ValueObject patterns).
