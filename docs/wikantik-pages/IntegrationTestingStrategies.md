---
cluster: software-engineering
canonical_id: 01KQ0P44IN1TTBPRAC78X9P1V2
title: Integration Testing Strategies
type: article
tags:
- testing
- integration-testing
- testcontainers
- wiremock
summary: Strategies for verifying the interaction between components and external systems while maintaining test isolation and reliability.
auto-generated: false
---
# Integration Testing Strategies

Integration tests verify that different modules or services work together correctly. Unlike unit tests, they cross boundaries (Database, File System, Network) and are essential for catching "glue code" bugs.

## Isolation with TestContainers

The modern standard for integration testing is **TestContainers**. It allows you to spin up lightweight, throwaway instances of your real infrastructure (PostgreSQL, Redis, Kafka) inside Docker containers during the test run.

**Benefits:**
-   Eliminates "it works on my machine but not in CI" issues.
-   Ensures a clean database state for every test run.
-   Tests real SQL/behavior, not a "fake" H2 database that might behave differently.

### Concrete Example: Postgres Integration

```java
@Testcontainers
class UserRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void shouldSaveAndRetrieveUser() {
        // Database is running in a real Docker container
        String jdbcUrl = postgres.getJdbcUrl();
        UserRepository repo = new UserRepository(jdbcUrl);

        repo.save(new User("bob", "bob@example.com"));
        Optional<User> found = repo.findByUsername("bob");

        assertTrue(found.isPresent());
        assertEquals("bob@example.com", found.get().getEmail());
    }
}
```

## Mocking External APIs (WireMock)

When your system depends on a 3rd party REST API (e.g., Stripe, Twilio), do not hit the real production/sandbox servers. Use **WireMock** to spin up a local HTTP server that returns pre-defined JSON responses.

```java
@Test
void shouldHandlePaymentFailure() {
    wireMockServer.stubFor(post(urlEqualTo("/v1/charges"))
        .willReturn(aResponse()
            .withStatus(402)
            .withBody("{\"error\": \"insufficient_funds\"}")));

    PaymentResult result = paymentClient.charge(100.0);
    assertEquals(PaymentStatus.FAILED, result.status());
}
```

## Contract Testing (Pact)

In microservice architectures, integration tests often break because the "Provider" changed their API and the "Consumer" didn't know. **Contract Testing** formalizes the agreement:
1.  **Consumer** defines a "Pact" (expected request/response).
2.  **Provider** verifies their implementation against the Pact.
This catches breaking changes before they hit production.

## Database Strategy: Rollback vs. Truncate

-   **Transaction Rollback:** Wrap each test in a transaction and roll it back at the end. Very fast, but doesn't test commit-time constraints (like unique indexes).
-   **Truncate/Clean:** Manually delete all data between tests. Slower but more realistic. **Standard for Wikantik:** Use `TRUNCATE` on all tables to ensure absolute isolation.

## Integration Testing Anti-Patterns

-   **Hitting Production/Staging:** Never hit shared environments. They are non-deterministic and you might accidentally send real emails or charge real credit cards.
-   **The "Big Bang" Test:** Trying to test the entire system end-to-end in one test. These are flaky and hard to debug. Test specific integrations (e.g., Web -> DB, Service -> API).
-   **Ignoring Timeouts:** Integration tests should always have a timeout. A hanging database connection should fail the test quickly, not block the CI pipeline for hours.
