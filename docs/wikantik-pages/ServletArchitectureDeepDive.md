---
canonical_id: 01KQ0P44WBY9CFWJ5YAW1Z0273
title: Servlet Architecture Deep Dive
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How the Servlet API actually works — request lifecycle, filters, listeners,
  context, and how Spring MVC and other frameworks sit on top of it.
tags:
- servlets
- jakarta-ee
- java
- web-architecture
- filters
related:
- SpringBootFundamentals
- WebServicesAndApis+Hub
- WebApplicationFirewalls
- HttpTwoAndHttpThree
hubs:
- Java Hub
---
# Servlet Architecture Deep Dive

The Servlet API is the foundation under most Java web frameworks. Spring MVC, Jersey, JAX-RS, raw web applications — all built on Servlet at the bottom. Modern Java backends (Spring WebFlux, reactive frameworks) increasingly bypass Servlet, but Servlet-based stacks remain dominant.

This page covers what Servlet actually does and how the major frameworks sit on it.

## The basics

A servlet is a Java class that handles HTTP requests:

```java
@WebServlet("/api/orders/*")
public class OrderServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // handle GET
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // handle POST
    }
}
```

The container (Tomcat, Jetty, Undertow) manages servlet lifecycle and dispatches requests.

## The request lifecycle

When an HTTP request arrives:

1. **Container receives request**: TCP connection, HTTP parsing, request object construction
2. **Servlet matching**: container finds the servlet whose mapping matches the URL
3. **Filter chain**: any filters configured for this URL run in order
4. **Servlet method invocation**: `doGet`, `doPost`, etc.
5. **Response writing**: servlet writes to `HttpServletResponse`
6. **Filter chain (reverse)**: filters' post-processing runs in reverse order
7. **Container sends response**: HTTP framing, TCP write

Each request runs on a thread from the container's thread pool. With virtual threads (Java 21+), this can be a virtual thread.

## Filters

Filters intercept requests:

```java
@WebFilter("/*")
public class LoggingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {

        long start = System.nanoTime();
        try {
            chain.doFilter(req, resp);
        } finally {
            long duration = System.nanoTime() - start;
            log.info("Request took {}ns", duration);
        }
    }
}
```

Common filter use cases:
- Authentication / authorization (verify credentials before request handling)
- Logging
- CORS
- Compression
- Security headers

Spring Security, Wikantik's `McpAccessFilter`, etc. — all servlet filters.

## Listeners

Listeners react to lifecycle events:

- `ServletContextListener`: application start/stop
- `HttpSessionListener`: session create/destroy
- `ServletRequestListener`: request start/end

Used for one-time initialization, cleanup, observability. Spring Boot's `ApplicationContext` lifecycle hooks ultimately bottom out in servlet listeners.

## Servlet context

A shared map across all servlets in the same application:

```java
ServletContext ctx = req.getServletContext();
ctx.setAttribute("startTime", Instant.now());
```

Used for application-wide configuration. In Spring Boot, configuration is more typically Spring beans, but the servlet context still exists underneath.

## Sessions

```java
HttpSession session = req.getSession();
session.setAttribute("userId", userId);
```

Servlet sessions are per-user, per-application state. In modern stateless designs, sessions are increasingly replaced by JWTs or other token-based auth. Where sessions persist, they're typically backed by a session store (Redis, etc.) for clustering.

## How Spring MVC sits on top

Spring's `DispatcherServlet` is a servlet. When configured (Spring Boot does this automatically), it's mapped at `/*` (or another configured path). All requests go through it.

Inside `DispatcherServlet`:

1. Request received
2. `HandlerMapping` finds the controller method matching the URL
3. `HandlerInterceptor`s run (Spring's parallel to filters, but Spring-specific)
4. Argument resolvers convert request data to method parameters
5. Controller method executes
6. Return value is processed by `HandlerExceptionResolver` or `MessageConverter`
7. Response written

Spring Security adds `Filter`s in the servlet chain that run before `DispatcherServlet`.

## Modern Servlet (Jakarta EE)

The Servlet API moved from `javax.servlet` (Java EE) to `jakarta.servlet` (Jakarta EE) in 2018. Modern code uses the `jakarta.servlet` package.

This affects:
- Spring 6+ (uses Jakarta)
- Tomcat 10+ (Jakarta)
- Tomcat 9 and earlier (Java EE / `javax`)

For older codebases, the migration is mostly mechanical (package renames). Some libraries provide both versions.

## The reactive alternative

Spring WebFlux and reactive frameworks bypass the Servlet API. They use Netty (or Reactor Netty) directly. The model is event-loop-based rather than thread-per-request.

When does reactive matter:
- Very high concurrency with low compute per request
- Streaming use cases (SSE, large file uploads)
- Backpressure management

When servlet model is fine:
- Most CRUD applications
- Workloads where the per-request work is non-trivial
- Teams not deeply familiar with reactive programming

With virtual threads (Java 21+), the servlet model's traditional weakness — blocking I/O tying up threads — is largely solved. The case for reactive narrows.

## Common failure patterns

- **Storing mutable state in the servlet instance.** Servlets are singletons; instance state is shared across threads. Use request-scoped variables.
- **Holding connections open in long-running servlets.** Without reactive support, this ties up container threads.
- **Mixing javax.servlet and jakarta.servlet.** During migration, easy to get wrong.
- **Heavy filter chains.** Each filter adds latency; minimize.
- **Sessions for stateless APIs.** Use tokens; stateless scales better.

## Further Reading

- [SpringBootFundamentals](SpringBootFundamentals) — Spring on top of servlets
- [Web Services and APIs Hub](WebServicesAndApis+Hub) — API design above servlet level
- [WebApplicationFirewalls](WebApplicationFirewalls) — Layer above servlet
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Protocol layer below
- [Java Hub](Java+Hub) — Cluster index
