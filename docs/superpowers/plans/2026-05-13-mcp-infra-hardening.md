# MCP Infrastructure Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five infrastructure-layer fixes to `/wikantik-admin-mcp` per `docs/superpowers/specs/2026-05-13-mcp-infra-hardening-design.md` — discriminating 503 body, Caffeine-backed rate limiter, bridge breadcrumb, IPv6 CIDR coverage, single-path instruction lookup.

**Architecture:** Five independent, small refactors inside `wikantik-admin-mcp`. No new third-party deps (Caffeine + commons-net are already in the BOM). No DB migration, no protocol change, no scope change to any tool. Each fix has a one-file (or two-file) blast radius and lands behind its own unit test.

**Tech Stack:** Java 21, JUnit 5, Mockito, Caffeine 3.2.3 (already in BOM), Log4j2, Tomcat 11.

**Spec:** `docs/superpowers/specs/2026-05-13-mcp-infra-hardening-design.md`

---

## File Structure

**Created files:**
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpAccessFilterFailClosedTest.java` (or add cases to existing `McpAccessFilterTest`)
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpCidrIPv6Test.java`
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigInstructionsTest.java` (or add cases to existing `McpConfigTest`)

**Modified files:**
- `wikantik-admin-mcp/pom.xml` — add Caffeine + commons-net dependency entries (BOM-resolved, no version pin needed)
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java` — Fix 1 (503 body) + Fix 4 documentation (IPv6)
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpRateLimiter.java` — Fix 2 (Caffeine swap)
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java` — Fix 2 (`rateLimiterMaxClients` accessor) + Fix 5 (collapse instruction lookup)
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` — Fix 3 (one comment)
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpRateLimiterTest.java` — extend for Caffeine semantics

---

## Task 1: Fail-closed JSON body + Retry-After

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java:119-124`
- Test: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpAccessFilterTest.java` (add a new test method; the class already exists)

- [ ] **Step 1: Write the failing test**

Append to `McpAccessFilterTest.java`:

```java
@Test
void failClosedReturns503WithJsonBodyAndRetryAfter() throws Exception {
    final Properties p = new Properties();
    // No keys, no CIDRs, no allowUnrestricted → fail-closed
    final McpConfig config = new McpConfig( p );
    final McpRateLimiter rl = new McpRateLimiter( 0, 0 );
    final McpAccessFilter filter = new McpAccessFilter( config, rl );

    final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
    when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
    final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
    final StringWriter body = new StringWriter();
    when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    final FilterChain chain = Mockito.mock( FilterChain.class );

    filter.doFilter( req, resp, chain );

    Mockito.verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    Mockito.verify( resp ).setContentType( "application/json" );
    Mockito.verify( resp ).setHeader( "Retry-After", "86400" );
    Mockito.verifyNoInteractions( chain );

    final String text = body.toString();
    org.junit.jupiter.api.Assertions.assertTrue(
            text.contains( "\"error\":\"mcp_access_unconfigured\"" ), text );
    org.junit.jupiter.api.Assertions.assertTrue(
            text.contains( "mcp.access.keys" ), "Body should name the config keys: " + text );
    org.junit.jupiter.api.Assertions.assertTrue(
            text.contains( "mcp.access.allowUnrestricted" ), text );
}
```

If `McpAccessFilterTest` doesn't already import `StringWriter`/`PrintWriter`/`Properties`/`Mockito.when`, add the imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpAccessFilterTest#failClosedReturns503WithJsonBodyAndRetryAfter test`
Expected: FAIL — `setHeader("Retry-After", "86400")` was never called, and body assertion mismatches.

- [ ] **Step 3: Replace the fail-closed branch**

Find lines 119-124 in `McpAccessFilter.java`:

```java
if ( failClosed ) {
    SECURITY.warn( "MCP request rejected: filter fail-closed (no auth configured), ip={}", remoteAddr );
    httpResp.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    httpResp.setContentType( "application/json" );
    httpResp.getWriter().write( "{\"error\":\"MCP not configured\"}" );
    return;
}
```

Replace with:

```java
if ( failClosed ) {
    SECURITY.warn( "MCP request rejected: filter fail-closed (no auth configured), ip={}", remoteAddr );
    httpResp.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    httpResp.setContentType( "application/json" );
    httpResp.setHeader( "Retry-After", "86400" );
    httpResp.getWriter().write(
            "{\"error\":\"mcp_access_unconfigured\","
            + "\"detail\":\"No API keys, CIDR allowlist, or mcp.access.allowUnrestricted=true. "
            + "Configure one of mcp.access.keys / mcp.access.allowedCidrs / mcp.access.allowUnrestricted "
            + "in wikantik-custom.properties to enable /wikantik-admin-mcp.\"}" );
    return;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpAccessFilterTest#failClosedReturns503WithJsonBodyAndRetryAfter test`
Expected: PASS.

- [ ] **Step 5: Run the rest of `McpAccessFilterTest` to make sure nothing regressed**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpAccessFilterTest test 2>&1 | tail -10`
Expected: BUILD SUCCESS, no failures.

- [ ] **Step 6: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpAccessFilterTest.java
git commit -m "fix(mcp): fail-closed returns discriminating 503 body + Retry-After"
```

---

## Task 2: Caffeine-backed rate limiter

**Files:**
- Modify: `wikantik-admin-mcp/pom.xml` — add Caffeine dependency
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpRateLimiter.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java` — add `rateLimiterMaxClients()`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpRateLimiterTest.java`

### Sub-task 2a: Config accessor first

- [ ] **Step 1: Write the failing test**

Append to `McpConfigBulkLimitTest.java` (or create a small `McpConfigRateLimitTest.java` if you prefer to keep the existing class focused):

```java
@Test
void defaultRateLimiterMaxClientsIs10000() {
    assertEquals( 10000, new McpConfig( new Properties() ).rateLimiterMaxClients() );
}

@Test
void rateLimiterMaxClientsZeroOrNegativeFallsBackToDefault() {
    final Properties p = new Properties();
    p.setProperty( "wikantik.mcp.rate_limit.max_clients", "0" );
    assertEquals( 10000, new McpConfig( p ).rateLimiterMaxClients() );
}

@Test
void rateLimiterMaxClientsHonoursPositiveValue() {
    final Properties p = new Properties();
    p.setProperty( "wikantik.mcp.rate_limit.max_clients", "500" );
    assertEquals( 500, new McpConfig( p ).rateLimiterMaxClients() );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigBulkLimitTest test-compile`
Expected: compile error — `rateLimiterMaxClients()` method doesn't exist.

- [ ] **Step 3: Add the accessor to `McpConfig`**

In `McpConfig.java`, near the other accessors:

```java
private static final int DEFAULT_RATE_LIMIT_MAX_CLIENTS = 10000;

public int rateLimiterMaxClients() {
    final String raw = props.getProperty( "wikantik.mcp.rate_limit.max_clients" );
    if ( raw == null || raw.isBlank() ) return DEFAULT_RATE_LIMIT_MAX_CLIENTS;
    try {
        final int v = Integer.parseInt( raw.trim() );
        if ( v <= 0 ) {
            LOG.warn( "wikantik.mcp.rate_limit.max_clients={} is not positive — falling back to default {}",
                    raw, DEFAULT_RATE_LIMIT_MAX_CLIENTS );
            return DEFAULT_RATE_LIMIT_MAX_CLIENTS;
        }
        return v;
    } catch ( final NumberFormatException e ) {
        LOG.warn( "wikantik.mcp.rate_limit.max_clients={} is not an integer — falling back to default {}",
                raw, DEFAULT_RATE_LIMIT_MAX_CLIENTS );
        return DEFAULT_RATE_LIMIT_MAX_CLIENTS;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigBulkLimitTest test`
Expected: all tests pass (3 new + existing 3 from the kg_curation work = 6 in that class, or whichever count is now).

### Sub-task 2b: Add Caffeine dep + swap the limiter internals

- [ ] **Step 5: Add Caffeine dep to wikantik-admin-mcp pom**

In `wikantik-admin-mcp/pom.xml`, inside `<dependencies>`:

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

(Version is resolved from the parent BOM at `wikantik-bom/pom.xml`.)

- [ ] **Step 6: Write the failing tests for the new eviction semantics**

In `McpRateLimiterTest.java`, append:

```java
@Test
void evictsClientEntryAfterTtl() {
    // ManualTicker lets us drive Caffeine's clock without sleeping.
    final FakeTicker ticker = new FakeTicker();
    final McpRateLimiter rl = new McpRateLimiter( 100, 10, 10000, ticker );

    rl.tryAcquire( "key:transient" );
    org.junit.jupiter.api.Assertions.assertTrue( rl.clientCacheSize() >= 1 );

    ticker.advance( Duration.ofHours( 1 ).plus( Duration.ofMinutes( 1 ) ) );
    rl.tryAcquire( "key:other" );  // trigger any age-based maintenance

    rl.invalidateNow();  // force Caffeine cleanup (test seam)
    org.junit.jupiter.api.Assertions.assertEquals( 1, rl.clientCacheSize(),
            "Stale entry should have been evicted; only 'key:other' remains." );
}

@Test
void capsCacheAtMaxClients() {
    final McpRateLimiter rl = new McpRateLimiter( 0, 10, 5, new FakeTicker() );
    for ( int i = 0; i < 50; i++ ) rl.tryAcquire( "client:" + i );
    rl.invalidateNow();
    org.junit.jupiter.api.Assertions.assertTrue( rl.clientCacheSize() <= 5,
            "Caffeine size cap should bound the map at 5 entries; was " + rl.clientCacheSize() );
}
```

Use `com.github.benmanes.caffeine.cache.Ticker` for the `FakeTicker` helper class (small inner record in the test file or a top-level helper).

- [ ] **Step 7: Run tests to verify they fail to compile**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpRateLimiterTest test-compile`
Expected: compile errors (`clientCacheSize()`, `invalidateNow()`, four-arg constructor missing).

- [ ] **Step 8: Replace `McpRateLimiter` internals with Caffeine**

Replace the contents of `McpRateLimiter.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter with global and per-client limits.
 *
 * <p>Each bucket tracks request timestamps within a 1-second window. Per-client
 * buckets live in a Caffeine cache with a hard size cap and 1-hour TTL after
 * the last write — replacing the previous {@code ConcurrentHashMap} +
 * probabilistic-cleanup pattern with deterministic eviction.</p>
 */
public class McpRateLimiter {

    private static final long WINDOW_NS = 1_000_000_000L;

    private final int globalLimit;
    private final int perClientLimit;
    private final boolean disabled;
    private final Ticker ticker;

    private final ConcurrentLinkedDeque< Long > globalBucket;
    private final Cache< String, ConcurrentLinkedDeque< Long > > clientBuckets;

    public McpRateLimiter( final int globalLimit, final int perClientLimit ) {
        this( globalLimit, perClientLimit, 10000, Ticker.systemTicker() );
    }

    public McpRateLimiter( final int globalLimit, final int perClientLimit,
                           final int maxClients, final Ticker ticker ) {
        this.globalLimit = globalLimit;
        this.perClientLimit = perClientLimit;
        this.disabled = globalLimit <= 0 && perClientLimit <= 0;
        this.ticker = ticker;
        this.globalBucket = globalLimit > 0 ? new ConcurrentLinkedDeque<>() : null;
        this.clientBuckets = Caffeine.newBuilder()
                .maximumSize( maxClients )
                .expireAfterWrite( Duration.ofHours( 1 ) )
                .ticker( ticker )
                .build();
    }

    public boolean tryAcquire( final String clientId ) {
        if ( disabled ) return true;

        final long now = ticker.read();

        if ( globalBucket != null ) {
            evictOld( globalBucket, now );
            if ( globalBucket.size() >= globalLimit ) return false;
        }

        ConcurrentLinkedDeque< Long > clientDeque = null;
        if ( perClientLimit > 0 ) {
            clientDeque = clientBuckets.get( clientId, k -> new ConcurrentLinkedDeque<>() );
            evictOld( clientDeque, now );
            if ( clientDeque.size() >= perClientLimit ) return false;
        }

        if ( globalBucket != null ) globalBucket.addLast( now );
        if ( clientDeque != null )  clientDeque.addLast( now );
        return true;
    }

    private static void evictOld( final ConcurrentLinkedDeque< Long > deque, final long now ) {
        while ( true ) {
            final Long head = deque.peekFirst();
            if ( head == null || now - head < WINDOW_NS ) break;
            deque.pollFirst();
        }
    }

    /** Test seam: returns approximate current size of the per-client cache. */
    long clientCacheSize() {
        clientBuckets.cleanUp();
        return clientBuckets.estimatedSize();
    }

    /** Test seam: force Caffeine maintenance to run synchronously. */
    void invalidateNow() {
        clientBuckets.cleanUp();
    }
}
```

Notes:
- Removed `STALE_THRESHOLD_NS` (Caffeine handles TTL).
- Removed `cleanupStaleEntries(...)` (deterministic eviction now).
- Kept `WINDOW_NS` + `evictOld(...)` for per-deque sliding window.
- The new constructor overload is package-private for tests; existing two-arg public constructor is preserved for `McpServerInitializer` callers.

- [ ] **Step 9: Add `FakeTicker` helper in `McpRateLimiterTest.java`**

Inside the test class:

```java
private static final class FakeTicker implements com.github.benmanes.caffeine.cache.Ticker {
    private long nanos = 0L;
    @Override public long read() { return nanos; }
    void advance( final java.time.Duration d ) { nanos += d.toNanos(); }
}
```

- [ ] **Step 10: Run tests, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpRateLimiterTest test`
Expected: all tests pass (existing + two new). Existing tests using the two-arg constructor still compile via the public ctor.

- [ ] **Step 11: Wire the new config into `McpServerInitializer` (or wherever the limiter is constructed in production)**

Find the call site that constructs `new McpRateLimiter(globalLimit, perClientLimit)`. Replace with:

```java
new McpRateLimiter( config.rateLimitGlobal(), config.rateLimitPerClient(),
        config.rateLimiterMaxClients(), com.github.benmanes.caffeine.cache.Ticker.systemTicker() )
```

If there's only one production call site (likely `McpServerInitializer` or similar), update it. If the existing tests use the two-arg ctor by convention, they continue working.

To find the call site:
```bash
grep -rnE "new McpRateLimiter\b" wikantik-admin-mcp/src/main
```

- [ ] **Step 12: Run the full module test suite**

Run: `mvn -pl wikantik-admin-mcp test 2>&1 | tail -10`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 13: Commit**

```bash
git add wikantik-admin-mcp/pom.xml \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpRateLimiter.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpServerInitializer.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpRateLimiterTest.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigBulkLimitTest.java
git commit -m "fix(mcp): Caffeine-backed rate limiter with bounded eviction"
```

Only add `McpServerInitializer.java` if you actually modified it; if a different file holds the production constructor call, swap that in.

---

## Task 3: Bridge-retirement breadcrumb

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` — the line that calls `KnowledgeSubsystemBridge.fromLegacyEngine(engine)`

- [ ] **Step 1: Locate the call**

Run:
```bash
grep -nE "KnowledgeSubsystemBridge.fromLegacyEngine" wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
```

Expected: one or two hits inside the `if ( kgService != null )` block.

- [ ] **Step 2: Add the comment**

Immediately above the first `KnowledgeSubsystemBridge.fromLegacyEngine(engine)` call site, add:

```java
// future: switch to engine.getKnowledgeSubsystem() when KnowledgeSubsystemBridge
// retires in Phase 9. See wikantik-main/.../KnowledgeSubsystemBridge.java javadoc
// for status.
```

- [ ] **Step 3: Compile-check**

Run: `mvn -pl wikantik-admin-mcp compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java
git commit -m "docs(mcp): breadcrumb for Phase 9 KnowledgeSubsystemBridge retirement"
```

---

## Task 4: IPv6 CIDR coverage — test first

**Files:**
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpCidrIPv6Test.java`
- Possibly modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java` (only if tests fail; this task branches on the result)

- [ ] **Step 1: Write the test class**

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive coverage for IPv6 CIDR matching on McpAccessFilter.
 *
 * <p>The matching code uses InetAddress.getByName() (which returns either an
 * Inet4Address or Inet6Address backed by a 4-byte or 16-byte array) and
 * manual bit-shifting against the prefix length. These tests verify that
 * symmetric math works correctly for both address widths.</p>
 */
public class McpCidrIPv6Test {

    @Test
    void exactHost128MatchesItself() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "::1/128" );
        assertTrue( matches( cidrs, "::1" ) );
        assertFalse( matches( cidrs, "::2" ) );
    }

    @Test
    void prefix32MatchesAddressesInRange() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "2001:db8::/32" );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertTrue( matches( cidrs, "2001:db8:abcd:ef01::42" ) );
        assertFalse( matches( cidrs, "2001:db9::1" ) );
        assertFalse( matches( cidrs, "fe80::1" ) );
    }

    @Test
    void linkLocalPrefix10Matches() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "fe80::/10" );
        assertTrue( matches( cidrs, "fe80::1" ) );
        assertTrue( matches( cidrs, "febf:ffff:ffff:ffff::1" ) );
        assertFalse( matches( cidrs, "fec0::1" ) );  // outside the /10 block
        assertFalse( matches( cidrs, "::1" ) );
    }

    @Test
    void prefix0MatchesEverythingForItsFamily() throws Exception {
        final var v6All = McpAccessFilter.parseCidrs( "::/0" );
        assertTrue( matches( v6All, "::1" ) );
        assertTrue( matches( v6All, "2001:db8::42" ) );
        // /0 IPv6 should NOT match an IPv4 address (different family / byte width)
        assertFalse( matches( v6All, "10.0.0.1" ) );
    }

    @Test
    void prefix127IsPenultimateBoundary() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "2001:db8::/127" );
        assertTrue( matches( cidrs, "2001:db8::0" ) );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertFalse( matches( cidrs, "2001:db8::2" ) );
    }

    @Test
    void mixedV4AndV6AllowlistRoutesByFamily() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "10.0.0.0/8, 2001:db8::/32" );
        assertTrue( matches( cidrs, "10.5.5.5" ) );
        assertTrue( matches( cidrs, "2001:db8::1" ) );
        assertFalse( matches( cidrs, "192.168.1.1" ) );
        assertFalse( matches( cidrs, "fe80::1" ) );
    }

    @Test
    void v4CallerAgainstV6CidrDoesNotMatch() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "::/0" );
        assertFalse( matches( cidrs, "10.0.0.1" ),
                "/0 IPv6 must not silently match a 4-byte IPv4 address" );
    }

    @Test
    void v6CallerAgainstV4CidrDoesNotMatch() throws Exception {
        final var cidrs = McpAccessFilter.parseCidrs( "0.0.0.0/0" );
        assertFalse( matches( cidrs, "::1" ),
                "/0 IPv4 must not silently match a 16-byte IPv6 address" );
    }

    private static boolean matches( final List< McpAccessFilter.CidrEntry > cidrs,
                                    final String addr ) throws Exception {
        final byte[] bytes = InetAddress.getByName( addr ).getAddress();
        for ( final var c : cidrs ) {
            if ( McpAccessFilter.matches( bytes, c ) ) return true;
        }
        return false;
    }
}
```

The test references `McpAccessFilter.parseCidrs(...)`, `McpAccessFilter.matches(...)`, and `McpAccessFilter.CidrEntry` — all already package-private static in the existing file (per the existing class structure at lines 74, 232, 252). No production code change needed yet.

- [ ] **Step 2: Run the tests**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpCidrIPv6Test test 2>&1 | tail -25`

**Branch A — all tests pass:** The existing bit-shift code IS correct for IPv6. Add a one-line Javadoc note to `McpAccessFilter.java` (above the class):

```
 * <p>CIDR allowlists support both IPv4 and IPv6 entries. Mixed allowlists
 * are honoured per-family — a v4 caller will only match v4 CIDRs, and a
 * v6 caller will only match v6 CIDRs (no cross-family matching).</p>
```

Skip to Step 5.

**Branch B — any test fails:** The bit-shift logic is broken for IPv6. Inspect `matches(...)` at `McpAccessFilter.java:232`. The most likely break is a missing length-mismatch guard (allowing a v4 byte[] to match against a v6 `CidrEntry`). Fix by adding a length-guard at the top of `matches`:

```java
static boolean matches( final byte[] addr, final CidrEntry cidr ) {
    if ( addr.length != cidr.network().length ) {
        return false;  // different address family — no match
    }
    // existing bit-shift body unchanged
    ...
}
```

If a deeper issue surfaces (e.g., the bit-shift math itself is broken for 16-byte arrays), reduce the failing test to a minimal case, fix the math, and re-run the suite.

- [ ] **Step 3: After Branch B fixes, re-run the suite**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpCidrIPv6Test test`
Expected: all 8 tests pass.

- [ ] **Step 4: Confirm no v4 regression**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpAccessFilterTest test`
Expected: BUILD SUCCESS. Any existing v4 CIDR tests should still pass.

- [ ] **Step 5: Commit**

If Branch A:
```bash
git add wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpCidrIPv6Test.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java
git commit -m "test(mcp): document and verify IPv6 CIDR support"
```

If Branch B:
```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpCidrIPv6Test.java
git commit -m "fix(mcp): IPv6 CIDR matching (length guard for mixed-family allowlists)"
```

---

## Task 5: Single-path instruction-file lookup

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java` — collapse the four-step classloader hunt
- Modify (or create): `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigTest.java` — add instruction-resolution tests

- [ ] **Step 1: Write failing tests**

In `McpConfigTest.java` (or a new `McpConfigInstructionsTest.java`):

```java
@Test
void instructionsPrefersFileOverrideWhenSpecified( @TempDir final java.nio.file.Path tmp ) throws Exception {
    final java.nio.file.Path override = tmp.resolve( "custom-instructions.txt" );
    java.nio.file.Files.writeString( override, "OVERRIDE INSTRUCTIONS" );
    final Properties p = new Properties();
    p.setProperty( "mcp.instructions.file", override.toString() );

    assertEquals( "OVERRIDE INSTRUCTIONS", new McpConfig( p ).instructions() );
}

@Test
void instructionsFallsBackToBundledWhenOverrideMissing() {
    final Properties p = new Properties();
    p.setProperty( "mcp.instructions.file", "/nonexistent/path/that/does/not/exist.txt" );
    final String result = new McpConfig( p ).instructions();
    org.junit.jupiter.api.Assertions.assertTrue(
            result != null && !result.isBlank(),
            "Should fall back to the bundled classpath resource when override is unreadable" );
}

@Test
void instructionsReturnsBundledResourceByDefault() {
    final String result = new McpConfig( new Properties() ).instructions();
    org.junit.jupiter.api.Assertions.assertTrue(
            result != null && !result.isBlank(),
            "Default instructions should load from the bundled classpath resource" );
}
```

(`@TempDir` import: `org.junit.jupiter.api.io.TempDir`.)

- [ ] **Step 2: Run tests; expect compile-pass but result mismatch**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigTest test 2>&1 | tail -15`
Expected: existing instruction-resolution code may or may not pass these — depends on the current four-step behaviour. The first new test (`prefersFileOverrideWhenSpecified`) should already pass if the current code prioritises the property. The second (`fallsBackToBundledWhenOverrideMissing`) may fail if the current code returns empty string on missing override file. Note the actual outcomes and continue.

- [ ] **Step 3: Replace the classloader hunt with a two-stage lookup**

In `McpConfig.java`, locate the current `instructions()` method (starts at line 106 per the earlier scan). Replace its body with:

```java
public String instructions() {
    final String overridePath = props.getProperty( "mcp.instructions.file" );
    if ( overridePath != null && !overridePath.isBlank() ) {
        try ( final java.io.InputStream in =
                java.nio.file.Files.newInputStream( java.nio.file.Path.of( overridePath ) ) ) {
            return new String( in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
        } catch ( final java.io.IOException e ) {
            LOG.error( "mcp.instructions.file={} is configured but unreadable; "
                    + "falling back to bundled resource: {}",
                    overridePath, e.getMessage() );
            // fall through to bundled resource
        }
    }
    try ( final java.io.InputStream in = McpConfig.class.getResourceAsStream(
            "/wikantik-mcp-instructions.txt" ) ) {
        if ( in != null ) {
            return new String( in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
        }
        LOG.warn( "Bundled instructions resource /wikantik-mcp-instructions.txt not found; "
                + "MCP server will serve an empty instructions field." );
        return "";
    } catch ( final java.io.IOException e ) {
        LOG.error( "Failed to read bundled instructions: {}", e.getMessage() );
        return "";
    }
}
```

Delete the now-unused parent-classloader and TCCL paths (lines ~199-225 in the existing file — verify the exact range when editing). Also delete any helper methods that are no longer called (e.g., a private `tryLoadFrom(ClassLoader, ...)` if it exists).

Update the method's Javadoc to spell out the contract:

```java
/**
 * Returns the MCP server instructions text, or empty string if none configured.
 *
 * <p>Resolution order is strictly two-stage:</p>
 * <ol>
 *   <li>If {@code mcp.instructions.file} is set, load that absolute filesystem
 *       path. On read failure, log an error and fall through to step 2.</li>
 *   <li>Load the bundled classpath resource {@code /wikantik-mcp-instructions.txt}
 *       via this class's own classloader (the webapp loader in production,
 *       the test classpath in tests). No TCCL or parent-classloader walk.</li>
 * </ol>
 *
 * <p>If both sources are unreadable, returns {@code ""} and logs at warn level.</p>
 */
```

- [ ] **Step 4: Run instruction tests**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigTest test`
Expected: all 3 new tests pass + existing tests pass.

- [ ] **Step 5: Run the full module suite**

Run: `mvn -pl wikantik-admin-mcp test 2>&1 | tail -10`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Verify the wire-level IT still happy with the simpler lookup**

The `McpInstructionsDriftIT` (in `wikantik-it-tests/.../McpInstructionsDriftIT.java`) reads the live deployed instructions and grep's for tool names. Re-build the WAR and run the IT for one IT module:

```bash
mvn -pl wikantik-admin-mcp install -DskipTests -q
mvn -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests \
        -Dit.test='McpInstructionsDriftIT' verify 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS, `Tests run: 2`.

- [ ] **Step 7: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigTest.java
git commit -m "fix(mcp): collapse instruction-file lookup to property + bundled resource"
```

---

## Task 6: Final verification

**Files:** none (build verification)

- [ ] **Step 1: Run the unit reactor sequentially**

Run: `mvn clean install -DskipITs 2>&1 | grep -E "BUILD|FAILURE!" | tail -5`
Expected: BUILD SUCCESS.

(Use serial — `-T 1C` is known to race the wikantik-war packaging step on this repo.)

- [ ] **Step 2: Run the full integration test reactor**

Run: `mvn clean install -Pintegration-tests -fae 2>&1 | grep -E "BUILD|FAILURE!" | tail -5`
Expected: BUILD SUCCESS, zero IT failures.

- [ ] **Step 3: Inspect catalina log for the new behaviour**

Spin up a quick fail-closed Tomcat (or just curl the deployed endpoint) and verify the JSON body and SECURITY.warn line. Optional but valuable for confidence:

```bash
# After re-deploying with deploy-local.sh and removing mcp.access.* config:
curl -i http://localhost:8080/wikantik-admin-mcp/
```

Expected: HTTP/1.1 503 + `Content-Type: application/json` + `Retry-After: 86400` + the discriminating JSON body. Tail `tomcat/tomcat-11/logs/catalina.out` for the matching `SECURITY` warn line.

This step is informational — failures here aren't blocking the commit, since the unit tests already cover the contract. But it's the "watch the logs" sanity check the user asked for.

---

## Self-Review

Spec coverage check:

| Spec section | Plan task |
|--------------|-----------|
| Fix 1 — 503 body + Retry-After | Task 1 |
| Fix 2 — Caffeine rate limiter + max_clients config | Task 2 (2a config, 2b implementation) |
| Fix 3 — Bridge breadcrumb | Task 3 |
| Fix 4 — IPv6 CIDR test-first | Task 4 (both Branch A and Branch B) |
| Fix 5 — Single-path instruction lookup | Task 5 |
| Testing matrix (5 unit test classes) | Tasks 1-5 each |
| Final verification | Task 6 |

All covered. No placeholders. Type consistency: `rateLimiterMaxClients()`, `clientCacheSize()`, `invalidateNow()`, `McpAccessFilter.parseCidrs(...)`, `McpAccessFilter.matches(...)`, `McpAccessFilter.CidrEntry` all consistent across tasks.

The only branching path is Task 4 Branch A vs B — both arms have their own commit message and explicit fix code.
