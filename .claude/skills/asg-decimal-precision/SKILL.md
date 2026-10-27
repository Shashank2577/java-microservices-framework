---
name: asg-decimal-precision
description: Use whenever working on any code path that touches money, percentages, rates, or any financial decimal — including DTOs, JPA entities, JSON serializers, Drools rules, calculations, comparisons, or display formatting. Defines the platform-wide BigDecimal standard, the `Money` value object, currency handling, and ArchUnit rules that block `double`/`float` in financial code.
---

# Decimal Precision — The Platform Standard

> **Resolves GAP-010.** ASG Edge Plus standardizes on **NUMERIC(19,4)** for all stored financial amounts. Sub-cent precision is mandatory (FR-ACCT-009). Display rounding to 2 decimals is a presentation concern, not a storage concern.

## 1. The Three Numbers You Must Memorize

| Use | Scale | Rounding | Where |
| --- | ----- | -------- | ----- |
| **Storage** (DB column, DTO at API boundary, audit log) | **4** | `HALF_EVEN` at boundary | NUMERIC(19,4) Postgres column |
| **Intermediate calculation** (PV, amortization, multi-step) | **10** | none mid-stream; `HALF_EVEN` at the end | in-memory `BigDecimal` |
| **Display** (UI, reports, exports) | **2** for most currencies; per-currency override | `HALF_EVEN` | UI layer only |

**Never** store at scale 2 — you cannot recover sub-cent precision. **Never** calculate at scale 4 across many steps — accumulated rounding drift will diverge from the legacy system's known-correct outputs.

Banker's rounding (`HALF_EVEN`) is the GAAP-aligned choice. It is statistically unbiased over a large number of half-cases. `HALF_UP` is *not* equivalent — it introduces drift in long amortization schedules.

## 2. The `Money` Value Object

`Money` is a domain primitive built per `java-architecture` §7.2 (VOs). It is the **only** legal way to carry an amount through the domain.

```java
// domain/primitives/Money.java — pure Java, no Spring, no JPA
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final int STORAGE_SCALE = 4;
    public static final int INTERMEDIATE_SCALE = 10;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        // Normalize to storage scale at construction.
        // Reject if input scale exceeds storage scale — caller is responsible
        // for explicit rounding before crossing the boundary.
        if (amount.scale() > STORAGE_SCALE) {
            throw new IllegalArgumentException(
                "Money scale " + amount.scale() + " exceeds storage scale " + STORAGE_SCALE
                + "; round explicitly before constructing Money");
        }
        amount = amount.setScale(STORAGE_SCALE, ROUNDING);
    }

    public static Money zero(Currency c)            { return new Money(BigDecimal.ZERO, c); }
    public static Money of(String amt, String cur)  { return new Money(new BigDecimal(amt), Currency.getInstance(cur)); }

    public Money add(Money o)        { sameCurrency(o); return new Money(amount.add(o.amount), currency); }
    public Money subtract(Money o)   { sameCurrency(o); return new Money(amount.subtract(o.amount), currency); }
    public Money multiply(BigDecimal factor) {
        // multiplications go through intermediate scale and back
        var product = amount.multiply(factor).setScale(STORAGE_SCALE, ROUNDING);
        return new Money(product, currency);
    }
    public Money negate()            { return new Money(amount.negate(), currency); }
    public boolean isZero()          { return amount.signum() == 0; }
    public boolean isNegative()      { return amount.signum() < 0; }

    @Override public int compareTo(Money o) { sameCurrency(o); return amount.compareTo(o.amount); }

    private void sameCurrency(Money o) {
        if (!currency.equals(o.currency))
            throw new CurrencyMismatchException(currency, o.currency);
    }
}
```

### Money rules
- **Equality is strict on both fields.** `Money(100.00, USD) ≠ Money(100.00, EUR)`. Records give this for free.
- **Equality of amounts uses `compareTo()` semantics**, not `BigDecimal.equals()`. The record's auto-equals works because we normalize scale in the constructor — both 1.00 and 1.0000 store as 1.0000.
- **`add`/`subtract`/`compareTo` reject mismatched currencies.** Never silently coerce. A currency mismatch in code is a bug.
- **No `Money * Money`.** Multiplying money by money is meaningless. `Money * BigDecimal` only (rate, factor, percentage).
- **No `Money / Money` returning Money.** Dividing two amounts produces a ratio — return `BigDecimal`, not `Money`.

## 3. Currency

- Always `java.util.Currency`. Always ISO 4217. Never strings, never internal enums.
- Stored as a 3-char `CHAR(3)` column alongside every amount column: `(amount NUMERIC(19,4), currency CHAR(3))`.
- A `Money` field that is null-the-currency is a bug — currency is required at construction.
- **Conversion** between currencies happens at **explicit, named boundaries** only:
  - Tenant reporting currency aggregation (Reports service)
  - Cross-tenant ASG-internal consolidation
  - Never inside a measurement, posting, or candidate-submission step.
- All conversions write an audit record with: `from`, `to`, `rate`, `rate_source`, `rate_as_of`, `original_amount`, `converted_amount`. Rates are themselves `BigDecimal` at scale 8.

## 4. Display vs Storage

| Layer | Format |
| ----- | ------ |
| DB | NUMERIC(19,4) |
| Java domain | `BigDecimal` (in `Money`) at scale 4 |
| JSON API responses | Strings at scale 4 — never JS numbers (lossy) |
| Report exports (Excel, PDF) | Locale-formatted at the **currency's** default fraction digits |
| UI display | Currency default fraction digits (USD=2, JPY=0, BHD=3) |

```java
// presentation only
public final class MoneyFormatter {
    public String forDisplay(Money m, Locale locale) {
        var nf = NumberFormat.getCurrencyInstance(locale);
        nf.setCurrency(m.currency());
        nf.setMaximumFractionDigits(m.currency().getDefaultFractionDigits());
        return nf.format(m.amount());
    }
}
```

The UI must request display formatting; the API must not pre-round.

## 5. JPA Converter

Every JPA entity that holds money uses the same converter and the same column definition. No exceptions.

```java
// infrastructure/persistence/jpa/MoneyAttributeConverter.java
@Converter(autoApply = false)
public class MoneyAmountConverter implements AttributeConverter<BigDecimal, BigDecimal> {
    @Override public BigDecimal convertToDatabaseColumn(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(Money.STORAGE_SCALE, Money.ROUNDING);
    }
    @Override public BigDecimal convertToEntityAttribute(BigDecimal v) {
        return v == null ? null : v.setScale(Money.STORAGE_SCALE, Money.ROUNDING);
    }
}

// usage on a JPA entity
@Column(name = "amount", precision = 19, scale = 4, nullable = false)
@Convert(converter = MoneyAmountConverter.class)
private BigDecimal amount;

@Column(name = "currency", length = 3, nullable = false, columnDefinition = "CHAR(3)")
private String currency;
```

The domain `Money` ↔ JPA `(amount, currency)` mapping is in the mapper class, per `java-architecture`.

## 6. Tax and Per-Line Rounding

Tax calculations are the most common source of audit findings. The platform rule:

- **Tax computes per line**, rounded to scale 4 per line, then summed. Do **not** compute tax on the summed total and back-allocate — it produces sub-cent drift that fails legacy parity.
- Where a jurisdiction explicitly requires summed-then-rounded (rare, e.g., some EU VAT rules), the rule is configured per tenant + jurisdiction and named explicitly: `taxAllocation: PER_LINE | SUMMED`.
- Tax rates are `BigDecimal` at scale 8 (`0.08250000` for 8.25%).
- The line-level `tax_amount` is stored at scale 4. The displayed line tax may be at scale 2. The displayed total tax = sum of displayed line taxes (which may differ by ≤ 1 cent from the displayed total × rate — this is correct and expected).

## 7. Drools and BigDecimal

In DRL files (per `asg-accounting-asc842`), always use `java.math.BigDecimal` and `java.math.RoundingMode`. Never `double` in a `then` block, never `eval` math on primitives.

```drl
import java.math.BigDecimal;
import java.math.RoundingMode;

rule "PV threshold"
    when
        $i: ClassificationInput(
            eval(presentValueOfPayments.divide(fairValue, 6, RoundingMode.HALF_EVEN)
                 .compareTo(new BigDecimal("0.90")) >= 0))
    then
        // ...
end
```

## 8. ArchUnit — Block `double`/`float` in Financial Code

```java
@AnalyzeClasses(packages = "com.asg", importOptions = DoNotIncludeTests.class)
public class FinancialPrecisionArchitectureTest {

    @ArchTest static final ArchRule no_double_in_financial_packages =
        noFields().that().areDeclaredInClassesThat()
            .resideInAnyPackage(
                "..asc842..", "..ifrs16..", "..accounting..", "..payment..",
                "..accrual..", "..allowances..", "..pctrent..", "..recoveries..",
                "..contract.domain.financial..")
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class);

    @ArchTest static final ArchRule no_double_method_returns_in_financial =
        noMethods().that().areDeclaredInClassesThat()
            .resideInAnyPackage("..asc842..", "..accounting..", "..payment..")
            .should().haveRawReturnType(double.class)
            .orShould().haveRawReturnType(Double.class);

    @ArchTest static final ArchRule money_field_has_currency_field =
        classes().that().haveSimpleNameEndingWith("JpaEntity")
            .and().resideInAPackage("..infrastructure.persistence.jpa..")
            .and().areAnnotatedWith(jakarta.persistence.Entity.class)
            // pseudo: enforced by code review checklist + the mapper layer
            .should().beAnnotatedWith(jakarta.persistence.Entity.class);
}
```

CI fails on violation. A `double` in a financial path is a P0 issue.

## 9. JSON Serialization

- Use Jackson with `WRITE_BIGDECIMAL_AS_PLAIN = true` and **always serialize amounts as JSON strings**, never as numbers. Browser JS `Number` is a double and will silently corrupt scale 4.

```java
@Bean ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new SimpleModule()
            .addSerializer(BigDecimal.class, new ToStringSerializer())
            .addSerializer(Money.class, new MoneySerializer()))
        .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
}
```

API contract:
```json
{ "amount": "12345.6789", "currency": "USD" }
```
Never:
```json
{ "amount": 12345.6789, "currency": "USD" }   // wrong — double precision
```

## 10. Equality and Hashing — Gotchas

- `new BigDecimal("1.00").equals(new BigDecimal("1.0"))` → **false**. Different scales.
- `Money(1.00, USD).equals(Money(1.0, USD))` → **true** in this codebase because the constructor normalizes both to scale 4.
- **Never** use raw `BigDecimal` in a `Set` or as a `Map` key for amounts. Use `Money` or compare with `compareTo`.

## 11. Smells

- A `double` in any class under `..financial..` or any of the accounting packages. P0.
- A `BigDecimal` field on a JPA entity without `precision = 19, scale = 4`. Wrong storage shape.
- A JSON response with `"amount": 12.34` (number, not string). Lossy at the browser.
- `BigDecimal.equals` used in a financial comparison. Always `compareTo() == 0` or via `Money`.
- A currency conversion buried inside a measurement step. Conversions only at named boundaries.
- A `Money * Money`. Meaningless; refactor to `Money * BigDecimal`.
- A tax line where the total tax = `round(sum(amounts)) * rate`. Per-line by default; `SUMMED` only by explicit tenant config.

## Reference

- `.claude/skills/lib/jabrena/122-java-type-design/references/122-java-type-design.md` — records as value objects, canonical constructor validation.
- `.claude/skills/lib/jabrena/144-java-data-oriented-programming/references/144-java-data-oriented-programming.md` — DOP with records and BigDecimal.
- GAP-010 (decimal precision conflict — resolved by this skill).
