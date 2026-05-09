---
cluster: software-engineering
canonical_id: 01KQ0P44UN1TTBPRAC78X9P1V1
title: Unit Testing Best Practices
type: article
tags:
- testing
- tdd
- junit
- best-practices
summary: Tactical guide to writing high-signal unit tests, focusing on isolation, table-driven patterns, and the FIRST principles.
auto-generated: false
---
# Unit Testing Best Practices

Unit tests verify the smallest testable parts of an application in isolation. High-quality unit tests act as executable documentation and provide a safety net for refactoring.

## The FIRST Principles

-   **Fast:** Tests should run in milliseconds so they can be executed after every code change.
-   **Isolated:** Tests should not depend on each other or external systems (DB, Network).
-   **Repeatable:** Running the test 100 times should yield the same result.
-   **Self-Validating:** No manual interpretation of results; it passes or fails.
-   **Thorough:** Cover edge cases, nulls, and boundary conditions, not just the "happy path."

## Anatomy of a Good Test

Use the **Arrange-Act-Assert** (AAA) pattern to keep tests readable.

```java
@Test
void shouldCalculateDiscountForPremiumUser() {
    // Arrange
    User user = new User("Alice", UserTier.PREMIUM);
    PricingEngine engine = new PricingEngine();

    // Act
    double price = engine.calculatePrice(100.0, user);

    // Assert
    assertEquals(80.0, price, "Premium users should get a 20% discount");
}
```

## Table-Driven Tests (Parameterized)

Instead of writing five tests for five different inputs, use parameterized tests to map inputs to expected outputs. This is the standard for complex logic.

```java
@ParameterizedTest
@CsvSource({
    "10, 2.0",
    "50, 10.0",
    "100, 20.0",
    "0, 0.0"
})
void shouldCalculateCorrectTax(double amount, double expectedTax) {
    TaxCalculator calc = new TaxCalculator(0.20);
    assertEquals(expectedTax, calc.calculate(amount));
}
```

## Mocking and Boundaries

Use mocks (e.g., Mockito) only for **external boundaries** or complex dependencies you don't control. Do not mock internal logic or data objects (POJOs/Records).

-   **Good Mock:** Mocking a `PaymentGateway` that hits a 3rd party API.
-   **Bad Mock:** Mocking a `List` or a simple `User` object.

## Common Pitfalls

1.  **Testing Implementation, Not Behavior:** If you rename a private method and the test breaks, your test is too brittle. Assert on the output, not the internal calls.
2.  **The "Slow Unit Test" Oxymoron:** If a test hits a database or starts a Spring context, it is an **Integration Test**, not a Unit Test. Move it to the appropriate suite.
3.  **Over-Mocking:** If your test setup is 50 lines of `when(...).thenReturn(...)` for a 5-line method, your class probably has too many responsibilities (SRP violation).
4.  **Assertion Roulette:** Multiple assertions in one test without clear messages. If it fails, you won't know which one failed without a debugger.

## Verification Checklist

-   [ ] Does the test fail if I break the code? (Verify the test is actually testing something).
-   [ ] Is there a clear "Reason for Failure" in the assertion message?
-   [ ] Are boundary conditions (0, -1, null, max_int) covered?
-   [ ] Can I run this test without internet access or a database?
