# MCP Infrastructure Hardening — Design

**Date:** 2026-05-13
**Status:** Approved, awaiting implementation plan
**Surface:** `/wikantik-admin-mcp` (and applies symmetrically to `/knowledge-mcp` where the same plumbing is shared)
**Related:** [KG Curation on MCP Design](2026-05-13-kg-curation-mcp-design.md), `project_api_key_admin_9b.md` memory

## Motivation

External review of `/wikantik-admin-mcp` surfaced five infrastructure-layer
concerns. None are functional defects (the endpoint serves tools correctly),
but each is a real DX or security-correctness gap that bites operators:

1. **503 fail-closed obscures a config error as a system-health error.**
2. **`ConcurrentHashMap`-backed rate limiter with 1-in-100 probabilistic cleanup
   can balloon under noisy-neighbour bursts.**
3. **`McpToolRegistry` calls `KnowledgeSubsystemBridge.fromLegacyEngine()` — a
   path documented as a legacy escape hatch that Phase 9 will retire — and
   bakes that coupling into the new KG curation registration.**
4. **CIDR matching uses homegrown bit-array math; IPv6 support is undocumented
   and untested.**
5. **`McpConfig.instructions()` walks four classloaders in priority order;
   debugging "why did the agent see old instructions?" requires understanding
   the full Tomcat classloader hierarchy.**

None of these are caused by the just-shipped KG curation work, but the work
brought them into focus. Tightening the infra under the four new write tools
is the right time.

## Goals

1. Operator who forgets to configure `mcp.access.*` sees an actionable error,
   not a misleading 503.
2. Rate-limiter heap cannot grow unbounded under burst load; eviction is
   deterministic, not probabilistic.
3. Curator-tool registration carries a breadcrumb for the Phase 9 typed-accessor
   migration so the coupling isn't silently forgotten.
4. IPv6 CIDR matching is verified by test, with documented support — or known
   to be broken and replaced.
5. Instruction-file resolution has one authoritative path, and that path is
   documented.

## Non-goals

- No change to the MCP protocol surface (tools, schemas, response shapes).
- No new auth tier or capability scopes — 9b unified-API-key admin still
  delivers that.
- No fail-on-startup behaviour change. The startup `SECURITY.warn` stays as
  the audible signal; the 503 body becomes the visible one.
- No tracing/metrics expansion. (Existing Prometheus counters stay as-is.)
- No new third-party deps. Caffeine 3.2.3 and commons-net 3.13.0 are both
  already in the parent BOM.

## Fix 1: Fail-closed 503 with discriminating JSON body

**File:** `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpAccessFilter.java`

Current behaviour (line 119-122): when `failClosed` is true (no keys, no CIDRs,
`allowUnrestricted` not set), the filter sends `503 Service Unavailable` with
no body. Operators searching `503` find resource-exhaustion answers in Stack
Overflow and never reach the actual cause.

**Change:** keep the 503 status (the service genuinely isn't ready), but write
a JSON body the operator can grep for in their request logs:

```json
{
  "error": "mcp_access_unconfigured",
  "detail": "No API keys, CIDR allowlist, or mcp.access.allowUnrestricted=true. Configure one of mcp.access.keys / mcp.access.allowedCidrs / mcp.access.allowUnrestricted in wikantik-custom.properties to enable /wikantik-admin-mcp."
}
```

Add `Retry-After: 86400` so a well-behaved automation client backs off rather
than hot-looping. Set `Content-Type: application/json`. Keep the existing
startup `SECURITY.warn` log line untouched.

**Rationale for 503 over 403/401:**
- 403 ("Forbidden") implies the caller's identity is the problem — it isn't.
  No identity has been configured to evaluate against.
- 401 ("Unauthorized") implies the caller should authenticate — but there's
  nothing to authenticate against until config lands.
- 503 with `Retry-After` accurately models "service not configured; do not
  retry quickly." The body removes the misleading "is the JVM healthy?"
  signal.

## Fix 2: Caffeine-backed rate limiter

**File:** `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpRateLimiter.java`

Current shape (134 lines): `ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>`
mapping client identity (IP or API-key prefix) to a window of request
timestamps. On every `n%100 == 0` admission, the limiter walks the map and
prunes entries with empty deques. Under a burst of 10k unique clients in one
minute, the map briefly holds 10k entries before cleanup runs.

**Change:** swap the underlying storage to Caffeine:

```java
private final Cache< String, ClientWindow > windows = Caffeine.newBuilder()
        .maximumSize( config.rateLimiterMaxClients() )      // default 10_000
        .expireAfterWrite( Duration.ofHours( 1 ) )
        .build();
```

`ClientWindow` is a small value class holding the existing deque + lock.
Caffeine handles eviction; we delete the probabilistic-cleanup branch entirely
(simpler code, deterministic heap bound, no behavioral regression for any
client active within the rolling window).

**New config knob:** `wikantik.mcp.rate_limit.max_clients` (default 10_000).
Add to `McpConfig.java` with the same fall-back-on-bad-value pattern used by
`kgCurationBulkLimit()`.

**Behaviour preserved:**
- Per-client window length unchanged.
- Per-client limit unchanged.
- Global rate limit unchanged.
- When a client's entry has been evicted (no traffic in 1h), the next request
  starts a fresh window — same observable behaviour as the current cleanup
  path, which leaves cleaned entries to be re-created on next admission.

## Fix 3: Bridge-retirement breadcrumb

**File:** `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`

At the `KnowledgeSubsystemBridge.fromLegacyEngine(engine)` call site, add:

```java
// future: switch to engine.getKnowledgeSubsystem() when
// KnowledgeSubsystemBridge retires in Phase 9 (see
// wikantik-main/.../KnowledgeSubsystemBridge.java header for status).
```

That's the entire fix. The bridge is a one-line coupling point; when Phase 9
deletes the bridge, the migration is a one-line edit at this site. The
breadcrumb ensures the migration isn't missed.

## Fix 4: IPv6 CIDR matching — test first, replace only if broken

**File:** `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpCidrIPv6Test.java` (new)

The existing `parseCidrs` and `matches` (in `McpAccessFilter`) use
`InetAddress.getByName()` (which returns `Inet4Address` or `Inet6Address` —
backed by 4-byte or 16-byte arrays) and manual bit-shifting against the prefix
length. The math should work for either width — but is unverified.

**Step 1:** Add a focused test class covering:
- `::1/128` (loopback exact match, 16-byte address)
- `2001:db8::/32` (broad block; verify both inside and outside addresses)
- `fe80::/10` (link-local)
- `/0` (matches everything — both v4 and v6 callers)
- `/127` boundary (penultimate prefix length)
- Mixed v4 + v6 allowlist (filter constructed with both kinds of entries)
- Reject mismatched-family attempts (a v6 caller against a v4 CIDR should
  *not* match, and vice versa).

**Step 2:** Run the tests against the existing code.
- If they all pass: add a sentence to `McpAccessFilter`'s Javadoc declaring
  "Supports both IPv4 and IPv6 CIDR entries; mixed allowlists are honoured."
- If any fail: replace the v4 path with `org.apache.commons.net.util.SubnetUtils`
  (handles v4 robustly) and write a small `Ipv6CidrMatcher` for v6. Commons-net
  is v4-only; v6 needs its own matcher. The matcher is straightforward against
  the 16-byte `getAddress()` output but should land with the tests it passes.

This is "evidence-driven repair" — we don't rewrite an auth path on suspicion.

## Fix 5: Instruction-file lookup — single authoritative path

**File:** `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java`

Current shape (approx): `instructions()` tries, in order, the system property
`mcp.instructions.file` as a filesystem path, then the classpath via
`getClass().getResourceAsStream(...)`, then the parent classloader, then the
thread context classloader. Four candidate paths means four bugs in
"which file did the agent actually see?".

**Change:** collapse to a deterministic two-step:

```java
public String instructions() {
    final String overridePath = props.getProperty( "mcp.instructions.file" );
    if ( overridePath != null && !overridePath.isBlank() ) {
        // explicit operator override — absolute filesystem path
        try ( final InputStream in = Files.newInputStream( Path.of( overridePath ) ) ) {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        } catch ( final IOException e ) {
            LOG.error( "mcp.instructions.file={} is configured but unreadable: {}",
                    overridePath, e.getMessage() );
            // fall through to bundled default rather than serving an empty string
        }
    }
    // bundled resource — single classpath lookup via this class's loader
    try ( final InputStream in = McpConfig.class.getResourceAsStream(
            "/wikantik-mcp-instructions.txt" ) ) {
        if ( in != null ) return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
    } catch ( final IOException e ) {
        LOG.error( "Failed to read bundled instructions: {}", e.getMessage() );
    }
    return "";
}
```

Drop the `getParent()` walk and the TCCL fallback entirely. The bundled
resource lives at `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`
and is loaded via the WAR's classloader — the same loader that loaded
`McpConfig` itself. Tests that need to override the bundled file use the
system property path.

Update `McpConfig`'s class Javadoc to spell out the two-stage contract.

## Testing

| Fix | Unit test |
|-----|-----------|
| 1 | `McpAccessFilterTest.failClosedReturns503WithJsonBody` — assert status, body shape, Retry-After header, Content-Type. |
| 2 | `McpRateLimiterTest.evictsClientAfter1hOfInactivity`, `…hardCapsAt10kEntries` — drive synthetic time via a `Ticker` mock. |
| 3 | None — comment only. |
| 4 | `McpCidrIPv6Test` — listed above (~7 cases). |
| 5 | `McpConfigInstructionsTest.preferSystemPropertyOverride`, `…fallsBackToBundledWhenOverrideMissing`, `…returnsEmptyWhenBothMissing`. |

No new integration tests. The wire-level `McpInstructionsDriftIT` and
`McpProtocolIT` already exercise the live deployment.

## Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.mcp.rate_limit.max_clients` | `10000` | Hard cap on Caffeine cache size. Bad values fall back to default with a startup warn (matches `kgCurationBulkLimit` convention). |

No other new properties.

## Migration / Rollout

Single commit per fix (or per logical pair). No DB migration. No schema
change. Existing tests pass unchanged. The 503 response body is additive
(callers ignoring bodies are unaffected); the rate-limiter swap is internal;
the instruction-file simplification removes lookup paths nobody depends on
in production. Test the simplification by running the existing
`McpInstructionsDriftIT` — if it passes, no other client cared.

## Open questions

None. All five fixes have clear scope. The IPv6 path is the only one with
branching outcomes (replace vs. document) and the spec lays out both arms.
