---
canonical_id: 01KQ0P44WTS6Q8E11NMTNJCCE4
title: Spring Boot Fundamentals
type: article
cluster: java
status: active
date: '2026-04-26'
summary: Spring Boot's core concepts — starter dependencies, auto-configuration, the
  application context, and the conventions that make rapid development possible without
  losing extensibility.
tags:
- spring-boot
- spring
- java
- framework
- auto-configuration
related:
- MavenMultiModuleProjects
- JpaAndHibernatePatterns
- ServletArchitectureDeepDive
- JavaCollectionsFramework
hubs:
- Java Hub
---
# Spring Boot Fundamentals

Spring Boot is the dominant Java application framework. It packages Spring Framework with sensible defaults, embedded servers, and dependency starters that turn "build a web application" from a hundred lines of XML into a single annotation. Most modern Java backend code runs on Spring Boot or something close to it.

This page covers the core concepts — the parts you need to understand to use Spring Boot effectively, not the encyclopedic catalog of every annotation.

## What Spring Boot adds to Spring

Spring Framework provides:
- IoC container (managed beans)
- Dependency injection
- Aspect-oriented programming
- Spring MVC (web framework)
- Spring Data (data access abstraction)

Spring Boot adds:
- Auto-configuration based on classpath
- Starter dependencies (curated transitive dependencies)
- Embedded servers (Tomcat by default; Jetty, Undertow, Netty available)
- Production-ready features (metrics, health checks, configuration externalization)
- Convention-over-configuration defaults

The trade-off: Spring Boot's "magic" auto-configuration is convenient but can be opaque when something goes wrong. Understanding what Spring Boot is doing is necessary for non-trivial debugging.

## The application class

A Spring Boot application starts from a class with `@SpringBootApplication`:

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`@SpringBootApplication` combines:
- `@SpringBootConfiguration` (Spring Boot's `@Configuration`)
- `@EnableAutoConfiguration` (the magic)
- `@ComponentScan` (find beans starting from this package)

By convention, `@SpringBootApplication` lives at the top of the package hierarchy; component scanning finds everything underneath.

## Beans

A Spring "bean" is an object managed by the IoC container. Three ways to declare:

### `@Component` family on a class

```java
@Component
public class OrderService { /* ... */ }

@Service     // semantic alias for @Component
@Repository  // for data access
@Controller  // for web controllers
@RestController  // controller + @ResponseBody
```

The container finds these via `@ComponentScan`. Annotated classes become beans automatically.

### `@Bean` on methods in a `@Configuration` class

```java
@Configuration
public class AppConfig {
    @Bean
    public DataSource dataSource() {
        return new HikariDataSource();
    }
}
```

Useful for creating beans from third-party code (no annotations available) or for conditional bean creation.

### `@SpringBootApplication` itself

The application class is also a `@Configuration` class; you can declare `@Bean` methods directly on it for small applications.

## Dependency injection

Inject dependencies via constructor (preferred):

```java
@Service
public class OrderService {
    private final OrderRepository repository;
    private final NotificationService notifications;

    public OrderService(OrderRepository repository, NotificationService notifications) {
        this.repository = repository;
        this.notifications = notifications;
    }
}
```

The container finds the matching beans and passes them in. `final` fields make the dependencies clearly required.

Field injection (`@Autowired` on a field) works but is discouraged: harder to test, hides dependencies, prevents `final` fields.

## Auto-configuration

The signature feature. Based on what's on the classpath, Spring Boot configures things automatically:

- Classpath has `spring-boot-starter-web`? Configure embedded Tomcat, Spring MVC, Jackson.
- Classpath has `spring-boot-starter-data-jpa`? Configure Hibernate, transaction management, JPA repositories.
- Classpath has `spring-boot-starter-actuator`? Configure health endpoints, metrics, info.

Each starter brings a transitive dependency tree plus the auto-configuration classes that wire it up.

You can:
- Override defaults via `application.properties` / `application.yaml`
- Provide custom `@Bean` methods that Spring Boot uses instead of its defaults
- Disable specific auto-configurations

## Configuration

External configuration via `application.properties`:

```properties
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost/mydb
spring.datasource.username=app
logging.level.org.springframework=INFO
```

Inject configuration with `@Value` (simple) or `@ConfigurationProperties` (typed):

```java
@ConfigurationProperties(prefix = "app.email")
public record EmailConfig(String host, int port, String username, String password) {}
```

Configuration profiles allow environment-specific settings:

```
application.properties
application-dev.properties
application-prod.properties
```

Activate a profile with `--spring.profiles.active=prod`.

## Web controllers

REST endpoints with `@RestController`:

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable String id) {
        return service.findById(id);
    }

    @PostMapping
    public Order create(@RequestBody @Valid CreateOrderRequest request) {
        return service.create(request);
    }
}
```

Methods become endpoints; parameters are bound from path, query, headers, or body.

## Data access

`@Repository` interfaces extending `JpaRepository`:

```java
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(OrderStatus status);
}
```

Spring Data implements the interface at runtime. Method names are parsed into queries (`findByStatus` → `WHERE status = ?`).

For complex queries, `@Query` annotations or QueryDSL.

## Testing

`@SpringBootTest` loads the full application context:

```java
@SpringBootTest
class OrderServiceTest {
    @Autowired
    OrderService service;

    @Test
    void shouldCreateOrder() {
        Order order = service.create(...);
        assertThat(order).isNotNull();
    }
}
```

For faster tests, slices like `@WebMvcTest` (controller layer only) or `@DataJpaTest` (data layer only) load a smaller context.

`@MockBean` replaces a real bean with a Mockito mock for tests.

## What to learn next

The fundamentals above cover ~80% of typical Spring Boot usage. Beyond that:

- **Spring Security** for authentication/authorization
- **Spring Data JPA / Spring Data MongoDB** for specific data stores
- **Spring Actuator** for production endpoints
- **Spring Cloud** for distributed systems patterns (config, service discovery, circuit breakers)
- **Spring Reactor / WebFlux** for reactive programming

## Common failure patterns

- **Field injection.** Constructor injection is easier to test and clearer about dependencies.
- **Tangled bean dependencies.** Circular dependencies (A depends on B depends on A) are usually a design error.
- **Heavy auto-configuration with no understanding.** When something doesn't work, you need to know what Spring Boot configured to debug.
- **`@SpringBootTest` for everything.** Slow; use slices when possible.
- **Hard-coded configuration.** Use `application.properties`; profile-specific files; environment variables.
- **Treating auto-configuration as immutable.** It's not — you can override anything with explicit beans.

## Further Reading

- [MavenMultiModuleProjects](MavenMultiModuleProjects) — Spring Boot project structure
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — Data layer underlying Spring Data JPA
- [ServletArchitectureDeepDive](ServletArchitectureDeepDive) — What Spring MVC sits on
- [JavaCollectionsFramework](JavaCollectionsFramework) — Common return types from Spring data
- [Java Hub](Java+Hub) — Cluster index
