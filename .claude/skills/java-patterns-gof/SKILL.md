---
name: java-patterns-gof
description: Use when designing classes, choosing between inheritance vs composition, building object construction logic, wiring strategies/handlers, or whenever a "which pattern fits here?" question arises in Java/Spring code. Covers the GoF creational, structural, and behavioral patterns with idiomatic Spring usage.
---

# GoF Design Patterns — Java + Spring Idioms

**Rule of thumb:** if Spring already provides a clean way to do it, use that. Patterns earn their place only when they make the code clearer, not because the textbook says so.

## Creational

### Factory Method
**Use when** the type to instantiate is decided by input but the family is closed.
```java
public sealed interface Notification permits Email, Sms, Push {}

@Component
class NotificationFactory {
    Notification create(NotificationRequest req) {
        return switch (req.channel()) {
            case EMAIL -> new Email(req.to(), req.subject(), req.body());
            case SMS   -> new Sms(req.to(), req.body());
            case PUSH  -> new Push(req.deviceId(), req.body());
        };
    }
}
```

### Abstract Factory
**Use when** you need *families* of related objects (e.g., per-tenant theme, per-DB-dialect query builder).
```java
public interface TenantInfraFactory {
    DataSource dataSource();
    KafkaTemplate<String, byte[]> kafkaTemplate();
    CacheManager cacheManager();
}
@Profile("aws") @Component class AwsInfraFactory implements TenantInfraFactory { ... }
@Profile("gcp") @Component class GcpInfraFactory implements TenantInfraFactory { ... }
```

### Builder
**Use for** value objects with >3 fields, optional fields, or invariants validated on `build()`.
Prefer **records + static factories** for ≤4 fields; use a Builder when there are many optional fields.
```java
public record Order(OrderId id, CustomerId customer, List<Line> lines, Money total, OrderStatus status) {
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private OrderId id; private CustomerId customer;
        private final List<Line> lines = new ArrayList<>();
        private Money total = Money.ZERO; private OrderStatus status = OrderStatus.DRAFT;
        public Builder id(OrderId v)       { this.id = v; return this; }
        public Builder customer(CustomerId v){this.customer = v; return this;}
        public Builder line(Line l)        { lines.add(l); total = total.add(l.subtotal()); return this; }
        public Order build() {
            if (id == null || customer == null) throw new IllegalStateException("id/customer required");
            return new Order(id, customer, List.copyOf(lines), total, status);
        }
    }
}
```
Avoid Lombok `@Builder` on domain types — hand-written builders document invariants.

### Prototype
Rare in Spring. Use a copy constructor or a record `with*` method instead of cloning.
```java
public record Address(String line1, String line2, String city, String zip) {
    public Address withZip(String zip) { return new Address(line1, line2, city, zip); }
}
```

### Singleton
**Don't write it yourself.** Default Spring bean scope is singleton. Avoid `enum`-singletons and static factories in app code; they hide dependencies.

## Structural

### Adapter
**Use when** an external API doesn't match the port your domain needs.
```java
// Domain port
public interface PaymentGateway { ChargeResult charge(Money amount, Card card); }
// Adapter to Stripe SDK
@Component
class StripePaymentGatewayAdapter implements PaymentGateway {
    private final StripeClient stripe;
    public ChargeResult charge(Money amount, Card c) {
        var r = stripe.charges().create(toStripeReq(amount, c));
        return new ChargeResult(r.getId(), r.getStatus());
    }
}
```

### Bridge
**Use when** two dimensions vary independently (e.g., `Report` × `Renderer`).
```java
interface Renderer { byte[] render(ReportData d); }   // PDF, HTML, CSV
abstract class Report { protected final Renderer r;  /* InvoiceReport, TaxReport */ }
```

### Composite
**Use for** tree-shaped domain — discounts (sum of children), pricing rules, org hierarchies.

### Decorator
**Use for** layering behavior (caching, retry, logging) without inheritance.
```java
public interface PricingService { Money quote(Order o); }

@Component @Primary
class CachingPricingService implements PricingService {
    private final PricingService delegate;          // the real one
    private final Cache cache;
    public Money quote(Order o) {
        return cache.get(o.id(), () -> delegate.quote(o));
    }
}
```
Spring AOP is decorator-based — `@Transactional`, `@Cacheable`, `@Retryable` all wrap the bean with a proxy.

### Facade
**Use to** front a complex subsystem with a small API. A "use-case" / "application service" is often a facade over domain + repos + gateways.

### Proxy
You almost never write this; Spring's CGLIB/JDK proxies handle `@Transactional`, `@Async`, `@Cacheable`, `@Secured`. Know it exists so you understand why `this.someMethod()` skips the advice.

### Flyweight
Rare. JPA's L1 cache + `Money` interning are practical examples. Don't reach for it without measured memory pressure.

## Behavioral

### Strategy
The workhorse. List-injected beans + a selector method (see §SRP example in `java-principles`).
```java
interface TaxStrategy { boolean supports(Country c); Money tax(Order o); }
@Service class TaxService {
    private final List<TaxStrategy> strategies;
    Money tax(Order o) { return strategies.stream().filter(s -> s.supports(o.country())).findFirst().orElseThrow().tax(o); }
}
```

### Chain of Responsibility
**Use for** validation, filters, middleware-style processing.
```java
interface OrderValidator { ValidationResult validate(Order o); }
// Spring orders them by @Order annotation; chain runs until first failure or all pass.
```
Spring Security filter chain, MVC interceptors, and Servlet filters are CoR.

### Command
**Use for** queued/async work, audit trails, undo. Maps cleanly to Kafka events and the **Outbox pattern**.
```java
public sealed interface OrderCommand permits PlaceOrder, CancelOrder, RefundOrder {}
@Service
class OrderCommandHandler {
    public void handle(OrderCommand c) {
        switch (c) {
            case PlaceOrder p  -> place(p);
            case CancelOrder x -> cancel(x);
            case RefundOrder r -> refund(r);
        }
    }
}
```

### Template Method
**Use when** the skeleton is fixed but steps vary. Spring's `JdbcTemplate`, `RestTemplate`, and abstract test classes are templates.
```java
abstract class AbstractImporter<T> {
    public final ImportResult run(InputStream in) {
        var rows = parse(in);
        var validated = validate(rows);
        return persist(validated);
    }
    protected abstract List<T> parse(InputStream in);
    protected abstract List<T> validate(List<T> rows);
    protected abstract ImportResult persist(List<T> rows);
}
```

### Observer
Spring's `ApplicationEventPublisher` + `@EventListener` = in-process observer.
**Caution:** in-process events are fire-and-forget within a JVM. For cross-service or durable, use Kafka + Outbox — not Spring events.

### State
**Use when** an entity's allowed operations depend on its current state (Order: Draft → Placed → Paid → Shipped → Delivered).
Implement via enum with abstract methods, or a state field + guard clauses in domain methods.
```java
public enum OrderStatus {
    DRAFT  { public OrderStatus place(Order o)  { return PLACED; } public OrderStatus cancel(Order o){ return CANCELLED;} },
    PLACED { public OrderStatus pay(Order o)    { return PAID;   } public OrderStatus cancel(Order o){ return CANCELLED;} },
    PAID   { public OrderStatus ship(Order o)   { return SHIPPED;} },
    SHIPPED, CANCELLED, DELIVERED;
    public OrderStatus place(Order o)  { throw illegal("place");  }
    public OrderStatus pay(Order o)    { throw illegal("pay");    }
    public OrderStatus ship(Order o)   { throw illegal("ship");   }
    public OrderStatus cancel(Order o) { throw illegal("cancel"); }
    private IllegalStateException illegal(String op){ return new IllegalStateException(op + " not allowed in " + this); }
}
```

### Mediator
**Use to** decouple peers that would otherwise reference each other. The application-service layer is often a mediator between domain and infrastructure. Avoid creating an "EverythingMediator" — that's a god-object.

### Iterator
Use `Stream` / `Iterable`. Don't hand-roll iterators.

### Visitor
**Use for** algorithms over a closed type hierarchy. Java 21 **sealed types + pattern matching** replace classical Visitor — no double-dispatch boilerplate.
```java
sealed interface Expr permits Num, Add, Mul {}
record Num(int v) implements Expr {}
record Add(Expr l, Expr r) implements Expr {}
record Mul(Expr l, Expr r) implements Expr {}

int eval(Expr e) {
    return switch (e) {
        case Num n -> n.v();
        case Add a -> eval(a.l()) + eval(a.r());
        case Mul m -> eval(m.l()) * eval(m.r());
    };
}
```

### Memento, Interpreter
Rare in business code. Skip unless you're building a parser or undo-stack.

## Pattern Selection — Decision Heuristics

| If you find yourself…                                       | Reach for                              |
| ----------------------------------------------------------- | -------------------------------------- |
| Writing `if (type == A) … else if (type == B) …`            | Strategy or sealed-type + pattern match|
| Writing setters for an object that should be immutable      | Builder or record + `with*` methods    |
| Wrapping a bean to add caching/retry/logging                | Decorator (or Spring AOP)              |
| Adapting an SDK to a domain port                            | Adapter                                |
| Modeling "this entity behaves differently in each state"    | State (enum or class-per-state)        |
| Validating in steps that can short-circuit                  | Chain of Responsibility                |
| Cross-service notification                                  | Outbox + Kafka (NOT Spring events)     |
| Fixed algorithm skeleton, varying steps                     | Template Method                        |
| Algorithm over a closed type set                            | Pattern matching on sealed types       |

**Anti-patterns to refuse:**
- Singleton with mutable state outside Spring.
- Service Locator (`ApplicationContext.getBean(...)` in business code).
- Deep inheritance (>2 levels) for behavior reuse — use composition.
- Manual proxies/decorators when AOP already provides the cross-cutting concern.
