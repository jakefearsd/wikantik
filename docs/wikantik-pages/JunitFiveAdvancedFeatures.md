---
canonical_id: 01KQ0P44RE042D7VP9DMCMGB2D
title: JUnit 5 Advanced Features
type: article
cluster: java
status: active
date: '2026-04-26'
summary: JUnit 5 features that go beyond basic test-case writing — parameterized tests,
  nested test classes, dynamic tests, extensions, the lifecycle hooks that earn their
  place.
tags:
- junit
- junit-5
- testing
- java
- test-frameworks
related:
- JavaTwentyOneFeatures
- JavaCollectionsFramework
- DebuggingStrategies
- SpringBootFundamentals
hubs:
- JavaHub
---
# JUnit 5 Advanced Features

JUnit 5 (Jupiter) replaced JUnit 4 in the late 2010s. Beyond the basic test-method-with-`@Test`-annotation pattern, it added features that change how more complex tests are written: parameterized tests, nested classes, dynamic tests, and a richer extension model. This page covers the features that pay off in practice.

## Parameterized tests

The same test logic with different inputs:

```java
@ParameterizedTest
@ValueSource(strings = {"alice", "bob", "carol"})
void shouldAcceptValidUsername(String username) {
    assertTrue(validator.isValid(username));
}
```

Multiple sources:

| Annotation | Use |
|------------|-----|
| `@ValueSource` | Single primitive or String value |
| `@CsvSource` | Multiple values per test, inline |
| `@CsvFileSource` | CSV file as source |
| `@MethodSource` | Method-provided arguments |
| `@EnumSource` | Enum values |
| `@ArgumentsSource` | Custom argument provider |

```java
@ParameterizedTest
@CsvSource({
    "alice@example.com, true",
    "invalid, false",
    "', false"
})
void shouldValidateEmails(String input, boolean expected) {
    assertEquals(expected, EmailValidator.isValid(input));
}
```

Reduces test duplication and makes coverage explicit.

## Nested test classes

`@Nested` for hierarchical organization:

```java
class OrderTest {
    @Nested
    class WhenOrderIsPending {
        @Test
        void canBeCancelled() { /* ... */ }

        @Test
        void cannotBeShipped() { /* ... */ }
    }

    @Nested
    class WhenOrderIsConfirmed {
        @Test
        void cannotBeCancelled() { /* ... */ }

        @Test
        void canBeShipped() { /* ... */ }
    }
}
```

Useful for representing state-dependent behavior. Each `@Nested` class can have its own setup; tests within share that setup.

## Dynamic tests

Tests generated at runtime:

```java
@TestFactory
Stream<DynamicTest> shouldHandleAllSupportedFormats() {
    return Stream.of("json", "xml", "yaml")
        .map(format -> DynamicTest.dynamicTest(
            "should parse " + format,
            () -> assertNotNull(parser.parse(format, sampleInput))));
}
```

Useful when test cases depend on runtime data (file system contents, database state, configuration).

## Test lifecycle

The standard hooks:

- `@BeforeAll`: once before all tests in the class (must be static unless `@TestInstance(Lifecycle.PER_CLASS)`)
- `@BeforeEach`: before each test method
- `@AfterEach`: after each test method
- `@AfterAll`: once after all tests in the class

`@TestInstance(Lifecycle.PER_CLASS)` reuses the same test instance across all methods, allowing non-static `@BeforeAll`. Useful for expensive setup.

## Assertions

JUnit 5 assertions:

```java
assertEquals(expected, actual);
assertTrue(condition);
assertNotNull(obj);
assertThrows(IllegalArgumentException.class, () -> service.process(invalid));
assertTimeout(Duration.ofSeconds(1), () -> longRunning());

// Multiple assertions; runs all even if some fail
assertAll(
    () -> assertEquals("abc", result.id()),
    () -> assertTrue(result.amount() > 0),
    () -> assertEquals(OrderStatus.PENDING, result.status())
);
```

For more readable assertions, AssertJ's fluent API is widely preferred:

```java
assertThat(result)
    .isNotNull()
    .extracting(Order::id, Order::amount, Order::status)
    .contains("abc", 99.0, OrderStatus.PENDING);
```

## Extensions

JUnit 5's `@ExtendWith` mechanism replaces JUnit 4 runners and rules. Extensions hook into the test lifecycle to provide framework integration.

### Common extensions

- `@ExtendWith(MockitoExtension.class)` — Mockito support
- `@ExtendWith(SpringExtension.class)` — Spring tests (often via `@SpringBootTest`)
- `@ExtendWith(MyExtension.class)` — custom extensions

### Custom extensions

For project-specific test infrastructure:

```java
public class TimingExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {
    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(NAMESPACE).put("startTime", System.nanoTime());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long duration = System.nanoTime() - (long) context.getStore(NAMESPACE).get("startTime");
        System.out.println(context.getDisplayName() + " took " + duration + "ns");
    }
}
```

Extensions earn their place when test infrastructure is shared across many tests.

## Display names

```java
@Test
@DisplayName("should reject invalid email formats")
void shouldRejectInvalid() { /* ... */ }
```

The display name appears in test reports. For tests with names that read as sentences, this is helpful.

For parameterized tests, the parameter values are in the display name automatically.

## Tagging

```java
@Test
@Tag("slow")
void integrationTest() { /* ... */ }
```

Useful with build tools to run subsets:

```bash
mvn test -Dgroups=fast
mvn test -Dgroups=integration
```

Maintains the unit/integration distinction without separate source directories.

## Conditional execution

Skip tests based on conditions:

```java
@Test
@EnabledOnOs(OS.LINUX)
void linuxOnlyTest() { /* ... */ }

@Test
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
void ciOnlyTest() { /* ... */ }

@Test
@DisabledIf("isLegacyMode")
void modernOnlyTest() { /* ... */ }
```

## Common failure patterns

- **One giant test class with hundreds of tests.** Hard to navigate; nest or split.
- **Mutable state shared across tests.** `@BeforeEach` should reset; otherwise tests interfere.
- **Sleep-based timing in tests.** Flaky; use `assertTimeout` or proper synchronization.
- **Mocking everything.** Tests pass while production fails. Test against real implementations where reasonable.
- **Skipping tests with `@Disabled` and forgetting them.** Run periodically; remove or fix.

## Further Reading

- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Modern features used in test code
- [JavaCollectionsFramework](JavaCollectionsFramework) — Test data structures
- [DebuggingStrategies](DebuggingStrategies) — Tests as debugging aid
- [SpringBootFundamentals](SpringBootFundamentals) — Spring's test slices
- [Java Hub](JavaHub) — Cluster index
