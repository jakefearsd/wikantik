---
title: Decorator Pattern
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- decorator-pattern
- design-patterns
- middleware
- composition
summary: Decorator pattern from GoF to modern middleware — wrapping behaviour
  without modifying the wrapped object. The HTTP middleware stack, Python's
  @decorator, retry/cache/log wrappers — all the same idea.
related:
- DesignPatternsOverview
- ObserverPattern
- AdapterPattern
hubs:
- SoftwareArchitecture Hub
---
# Decorator Pattern

Wrap an object in another object that adds behaviour, without modifying the wrapped object. The HTTP middleware chain. The Python `@decorator` syntax. Logging / caching / retrying wrappers. Each is the GoF Decorator pattern in a different dressing.

## The core idea

Both the wrapper and the wrapped implement the same interface. The wrapper holds a reference to the wrapped, delegates most calls, and adds its specific behaviour around or instead of those calls.

```java
interface Handler {
    Response handle(Request req);
}

class CachingHandler implements Handler {
    private Handler inner;
    private Cache cache;
    
    public Response handle(Request req) {
        Response cached = cache.get(req.cacheKey());
        if (cached != null) return cached;
        Response fresh = inner.handle(req);
        cache.put(req.cacheKey(), fresh);
        return fresh;
    }
}

// Compose
Handler base = new HttpHandler();
Handler cached = new CachingHandler(base);
Handler logged = new LoggingHandler(cached);
Handler retried = new RetryHandler(logged);

retried.handle(request);
```

Behaviours stack. Each layer is independent. Order matters — different layers go around or inside each other depending on the layout.

## Where it shows up

### HTTP middleware stack

Every web framework has it: Express, Koa, Django middleware, Spring filters, Rails Rack middleware, ASP.NET middleware.

```python
# Express-style
app.use(loggingMiddleware)
app.use(authMiddleware)
app.use(rateLimitMiddleware)
app.use(routeHandler)
```

Each middleware can:
- Inspect the request before it reaches the next layer.
- Inspect / modify the response after.
- Short-circuit (return early without calling the next).

This is the Decorator pattern at architecture level. The "next" function is the wrapped delegate.

### Language-level decorators

Python's `@decorator` syntax:

```python
@cached(ttl=60)
@retry(max_attempts=3)
@log_calls
def fetch_data(url):
    return http.get(url)
```

The function being decorated is wrapped by `log_calls`, then by `retry`, then by `cached`. Calling `fetch_data(url)` enters the cached layer first.

Same pattern; nicer syntax. JavaScript / TypeScript have decorators (TC39); Java has annotation processors that approximate this; C# has attributes.

### React Higher-Order Components (legacy)

```javascript
const ConnectedComponent = withRouter(withAuth(MyComponent));
```

HOCs decorate a component with extra props / behaviour. Still works; superseded by hooks for most cases (which are arguably a different pattern).

### Wrapper libraries

- **Polly** (.NET) — retry, circuit breaker, fallback. Each as a wrapper.
- **Resilience4j** (Java) — same.
- **`tenacity`** (Python) — retry decorator.
- **`backoff`** (Python) — same.

## Common decorator behaviours

The list of "things you wrap a function with" is short and recurs everywhere:

- **Logging** — log entry, exit, duration, errors.
- **Caching** — return cached result if available.
- **Retry** — re-invoke on transient failure.
- **Timeout** — abort if too slow.
- **Rate limit** — defer if too frequent.
- **Circuit breaker** — fail fast if downstream is degraded.
- **Authentication / authorization** — verify caller.
- **Tracing** — emit a span.
- **Metrics** — count, time, count by outcome.
- **Validation** — check inputs / outputs.

These compose. A typical service-call chain might be:

```
trace → metric → auth → ratelimit → circuit_breaker → retry → timeout → cache → actual_call
```

The depth of the chain is the cost; the modularity is the benefit.

## Order matters

The order of decorators changes behaviour. Examples:

`retry → cache`: each retry checks cache; first cache miss leads to actual call; subsequent retries within the call don't re-check cache.

`cache → retry`: cache check happens once; cache miss triggers the inner retry-wrapped call. Cache stores the post-retry result.

Different shapes. Pick deliberately.

Common gotchas:

- **Cache before timeout?** A timed-out call still gets cached if you wrap timeout outside cache. Probably wrong.
- **Auth before rate limit?** Rate limit unauthenticated requests = trivial DoS. Auth before rate limit = unauthenticated requests slip through limits. Order both early; rate-limit by IP for unauth, by user for auth.
- **Log before retry?** Each retry gets logged. Often wanted (visibility into retry behaviour); sometimes not (noise).

Document the order; review when adding new decorators.

## When the pattern shines

- **Cross-cutting concerns** — logging, security, caching, retry. Things you want around many functions / handlers.
- **Composable behaviours** — pick which to apply per route / per function.
- **Adding behaviour to existing classes you can't modify** — third-party APIs.

## When the pattern is overkill

- **Single-use behaviour** — just write the code in the function. Pattern adds indirection without benefit.
- **Mutually-incompatible behaviours** — if combining two decorators produces wrong behaviour, the pattern's composability isn't earning its keep.
- **Hot loops** — each decorator adds a function call. For tight inner loops, inline.

## A modern variant: function composition

In functional languages and increasingly mainstream languages with first-class functions, decorators are just function composition.

```haskell
processed = retry . cache . log $ baseFunction
```

Or in JavaScript / Python:

```javascript
const processed = retry(cache(log(baseFunction)));
```

Same pattern, no class machinery. Cleaner for many use cases.

## Anti-patterns

- **Hidden state in decorators.** "This logging decorator also writes to a global counter." Surprising; debugging harder. Keep decorators stateless or with explicit local state.
- **Decorators that change semantics in subtle ways.** A retry decorator that catches and swallows errors instead of propagating. Document; review.
- **Stacks too deep.** 12 decorators around one function. The combination is hard to reason about. Refactor.
- **Decorator-of-decorators.** "Apply this decorator to all decorators." Meta-programming gone too far.

## Implementation notes

For Python decorators specifically:

- Use `functools.wraps` to preserve the wrapped function's metadata.
- Decorators with arguments need an extra layer of indirection (`@cached(ttl=60)` is a function returning a decorator).
- Class decorators work too; less common.

For JavaScript / TypeScript:

- Stage-3 decorators (TC39) shipped in 2023; well-supported.
- Class decorators alter the class; method decorators alter the method.

For Java:

- Annotation + AOP (Spring AOP, AspectJ) approximates decorator. More machinery; same idea.

## Further reading

- [DesignPatternsOverview] — broader pattern context
- [ObserverPattern] — different classic pattern
- [AdapterPattern] — also wraps; for interface translation
