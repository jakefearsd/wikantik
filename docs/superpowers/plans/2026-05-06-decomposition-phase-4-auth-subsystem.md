# Phase 4: AuthSubsystem extraction — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** ready
**Estimated effort:** 4 days
**Goal:** wall off authentication and authorization behind a small typed `AuthSubsystem.Services` surface. Move the four core managers (`DefaultAuthenticationManager`, `DefaultAuthorizationManager`, `DefaultUserManager`, `DefaultGroupManager`) plus the policy / api-key holders into a single subsystem package. Decompose `SecurityVerifier` (802 LOC) along its three real responsibilities.

## Scope

**In:**
1. **`AuthSubsystem.Deps` + `.Services`** records housing the four core managers plus `WebContainerAuthorizer`, `ApiKeyServiceHolder`, `SecurityVerifier`-derived components.
2. **`AuthSubsystemFactory.create(Deps) → Services`** — pure factory consumed by `WikiEngine.initialize()` once the `(core, persistence)` services are in place.
3. **Decompose `SecurityVerifier`** (802 LOC) along the three responsibilities the existing code already segregates:
   - **`PolicyVerifier`** — security-policy reading (`policyPrincipals`, `policyRoleTable`, `isSecurityPolicyConfigured`, `verifySecurityPolicy`).
   - **`ContainerRoleVerifier`** — servlet-container role / `web.xml` checks (`containerRoleTable`, `webContainerRoles`).
   - **`JaasVerifier`** — JAAS configuration (`isJaasConfigured`, JAAS-related verify methods).
   - `SecurityVerifier` itself becomes a thin facade over these three, retaining its existing public surface (the admin "Security" page renders its tables) but delegating each section to the new helper.
4. **Migrate `engine.getManager(AuthorizationManager|UserManager|GroupManager|AuthenticationManager.class)` callers** in `wikantik-main` and `wikantik-rest` to `getSubsystems().auth().xxx()` (or `AuthSubsystemBridge.fromLegacyEngine(engine).xxx()` for non-servlet callers). 46 production callsites today; target close to zero after migration. As with leaf managers in Phase 2, `WikiEngine.getManager` keeps a typed-fallback so any remaining caller gracefully resolves through the typed subsystem.
5. **Subsystem-isolation test** for `AuthSubsystemFactory` against a Testcontainers Postgres + minimal `Deps` mocks.
6. **`KnowledgeSubsystem.Deps` consumes Auth** only if it actually needs an authorization decision today (audit during Ckpt 2 — Knowledge currently doesn't call `AuthorizationManager`, so likely no edge added in this phase).

**Out:**
- Splitting `wikantik-auth` into a separate Maven module. Keep types in `wikantik-main` under `com.wikantik.auth.subsystem.*` (mirror Persistence's pattern).
- Migrating the JAAS LoginModule chain into the subsystem. They live in `com.wikantik.auth.login.*` and bind via JAAS configuration; their construction is owned by JAAS, not the engine.
- Touching ACL parsing (`com.wikantik.api.permissions.*`) — those are pure data and stay where they are.
- Restructuring `WebContainerAuthorizer` internals. Move only.

## Design

### `AuthSubsystem` shape

```java
package com.wikantik.auth.subsystem;

public final class AuthSubsystem {

    public record Deps(
        CoreSubsystem.Services core,                  // properties + event bus
        PersistenceSubsystem.Services persistence,    // user / group DAOs (today JDBC under user/group managers)
        Engine engine                                 // legacy seam — needed by JAAS callbacks until ckpt 4
    ) {}

    public record Services(
        AuthenticationManager authentication,
        AuthorizationManager  authorization,
        UserManager           users,
        GroupManager          groups,
        WebContainerAuthorizer webContainerAuthorizer,
        ApiKeyService          apiKeys,                // backed by ApiKeyServiceHolder
        // SecurityVerifier-derived helpers (Ckpt 3):
        PolicyVerifier         policyVerifier,
        ContainerRoleVerifier  containerRoleVerifier,
        JaasVerifier           jaasVerifier
    ) {}
}
```

### `WikiSubsystems` evolution

Order: `(core, persistence, auth, knowledge)`. Auth depends on Core (typed properties) and Persistence (user/group DAOs); Knowledge doesn't depend on Auth in this phase (audit confirms).

### `SecurityVerifier` decomposition

Today's SecurityVerifier is a UI-driven aggregator: an admin presses "Security info" and sees three tables (policy / container roles / JAAS) plus a series of warnings. The class fans out into three concerns:

```java
public final class PolicyVerifier {
    public Principal[] policyPrincipals();
    public String policyRoleTable();
    public boolean isSecurityPolicyConfigured();
    void verify( Session session );           // posts session messages
}

public final class ContainerRoleVerifier {
    public String containerRoleTable() throws WikiException;
    public Principal[] webContainerRoles() throws WikiException;
    void verify( Session session ) throws WikiException;
}

public final class JaasVerifier {
    public boolean isJaasConfigured();
    void verify( Session session );
}
```

`SecurityVerifier` keeps its public surface so the existing admin JSP doesn't change; its constructor takes the three new helpers and delegates each public method through.

### Cross-subsystem dependency edges so far

```
Core ─────┐                                             ┌── KnowledgeSubsystem
          ├── PersistenceSubsystem ── AuthSubsystem ────┤
ServletContext / DataSource ──────┘                     └── (future) PageSubsystem
```

Auth is the third subsystem to declare an explicit cross-subsystem edge (after Persistence → Core, Knowledge → Core/Persistence). Establishes the pattern for Auth as the first non-leaf consumer.

## Checkpoint plan

Each checkpoint = one commit + the full IT reactor before commit. Subagents (Sonnet) run in parallel where the work decomposes into independent slices.

### Checkpoint 1 — Scaffolding (Opus)

- Create `AuthSubsystem.java` (`Deps` + `Services` records — leave `SecurityVerifier`-derived helpers stubbed/null until Ckpt 3).
- `AuthSubsystemFactory.create(Deps)` constructs / locates the four core managers and the policy / api-key holders.
- `AuthSubsystemBridge.fromLegacyEngine(Engine)` mirroring `KnowledgeSubsystemBridge`.
- `WikiSubsystems` adds `auth()` field, ordered `(core, persistence, auth, knowledge)`.
- `WikiEngine.initialize()` builds Auth between Persistence and Knowledge; exposes `getAuthSubsystem()`.
- `RestServletBase.getSubsystems()` reads the typed accessor + bridge fallback.
- `AuthSubsystemFactoryTest`.

**Behaviour change:** zero. New types exist; consumers haven't migrated.

### Checkpoint 2 — Migrate production consumers (parallel Sonnet ×2)

46 `engine.getManager(AuthenticationManager|AuthorizationManager|UserManager|GroupManager.class)` callsites in production. Two concurrent subagents:

- **Agent A:** REST resources (`wikantik-rest/.../*Resource.java`) — these have `getSubsystems()` cleanly available; switch to `getSubsystems().auth().xxx()`.
- **Agent B:** Non-servlet callers (filters, plugins, page services, wikantik-main internals) — switch to `AuthSubsystemBridge.fromLegacyEngine(engine).xxx()`.

`WikiEngine.getManager` already has a typed-fallback path; this checkpoint extends it for the four auth managers so any caller that escapes the migration still resolves through `coreSubsystem` / `authSubsystem` transparently.

### Checkpoint 3 — Decompose `SecurityVerifier` (Opus)

- Create `PolicyVerifier`, `ContainerRoleVerifier`, `JaasVerifier` under `com.wikantik.auth.subsystem.verify.*`.
- Move methods + private helpers from `SecurityVerifier` into the appropriate helper. Keep SQL/JAAS strings verbatim.
- `SecurityVerifier` becomes a facade: holds the three helpers, delegates each public method.
- New unit tests for the three helpers (split from existing `SecurityVerifierTest`).
- `AuthSubsystem.Services` exposes the three helpers.

### Checkpoint 4 — Migrate `SecurityVerifier` callers (parallel Sonnet ×2)

Audit `engine.getManager(SecurityVerifier.class)` callers (admin JSPs + verify resources) and switch to `getSubsystems().auth().policyVerifier()` / etc. for callers that only need one slice. Callers that genuinely want the whole-page composition stay on `SecurityVerifier`.

### Checkpoint 5 — Delete the SecurityVerifier facade if empty (Opus, conditional)

If Ckpt 4 leaves zero whole-aggregator callers, delete `SecurityVerifier` and remove it from `Services`. Otherwise: leave the facade in, document the reason, move to Ckpt 6.

### Checkpoint 6 — Close-out + metrics (Opus)

- `bin/metrics/measure.sh --label phase_4_close`.
- Spec & plan updated.
- ArchUnit baseline refrozen.

## Risks

1. **JAAS LoginModule lifecycle.** `DefaultAuthenticationManager` is constructed by `WikiEngine.initialize` and registers JAAS callbacks. Phase 4 keeps that construction in the engine (the factory wraps it), but the JAAS Configuration discovery still walks `wikantik.policy` from the classpath. Don't try to move that.

2. **`SecurityVerifier`'s static UI strings.** The class has dozens of message-key constants (`ERROR_POLICY`, `WARNING_JAAS`, etc.) that JSPs reference statically. Keep them as-is — no static-import changes — so the JSP layer's compile contract is preserved.

3. **Session-state coupling.** `SecurityVerifier` posts WARN/INFO/ERROR messages to the current `WikiSession`. The split helpers each take a `Session` parameter on their `verify(...)` methods so the message-posting contract stays intact (and a `null` session becomes a deliberate test mode).

4. **`WebContainerAuthorizer`.** It reads `web.xml`'s security-constraint stanzas via the servlet context. Keep its construction inside the factory but pass the `ServletContext` from `Deps` (Auth depends on `ServletContext` for this reason). When the engine boots without a servlet context (test fixtures), the authorizer is `null` and the factory logs an INFO. The `Services` field stays nullable — same pattern as `judgeService` in Knowledge.

5. **`ApiKeyServiceHolder`.** Currently a static singleton wrapping the `ApiKeyService` impl. Phase 4 wraps the holder behind the `apiKeys` field on `Services`. Consumers stop calling `ApiKeyServiceHolder.get()` and move to `getSubsystems().auth().apiKeys()`. The static holder stays for the duration of the migration; Phase 4 close-out marks it for removal in Phase 9.

## Done when

- `AuthSubsystem.Services` produces the four core managers + `WebContainerAuthorizer` + `apiKeys` + the three verifiers.
- 46 production `engine.getManager(Authentication|Authorization|User|Group.class)` callsites in main + rest are migrated; the `WikiEngine.getManager` fallback covers any escapees.
- `SecurityVerifier` is decomposed (or has a credible smaller facade with documented residual callers).
- `bin/metrics/measure.sh` shows `god_classes_over_800` drops by 1 (SecurityVerifier from 802 LOC to <300 LOC).
- Phase 5 (PageSubsystem) plan can begin against the new Auth foundation.
