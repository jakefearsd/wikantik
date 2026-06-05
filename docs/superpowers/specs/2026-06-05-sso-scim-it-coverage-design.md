# SSO + SCIM Integration-Test Coverage Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan
**Scope:** Integration-test infrastructure only. No production-code behavior change is required; this work adds end-to-end coverage and modernizes the SSO IdP container, and may surface (but does not plan) production bugs.

> **Revision 2026-06-05 (a):** Approach A was re-scoped after verification. The wire-level SCIM IT it
> originally called for **already exists** in `wikantik-it-test-rest` (`ScimUsersIT`, `ScimGroupsIT`),
> including the admin-never-granted invariant. Approach A is therefore *extending* those existing ITs
> with the two genuine gaps — not building a new `wikantik-it-test-scim` module. See "Approach A".
>
> **Revision 2026-06-05 (b):** Approach 2 originally folded SAML into the `-sso` module under
> `wikantik.sso.type=both`. During implementation this hit a wiki limitation: with two pac4j clients
> registered, the SAML ACS callback fails (`unable to find one indirect client for the callback`)
> because the SAML2Client's callback URL carries no `client_name` resolver (only OIDC's does). This
> path was never exercised before (OIDC and SAML always lived in separate single-client modules). Per
> user decision, SAML stays in its **own** module (`wikantik-it-test-sso-saml`, `type=saml`, single
> client) but is **converted from SimpleSAMLphp to Keycloak** — so both mocks are still replaced by
> Keycloak, with no production-code change. Cost: two Keycloak containers (one per module) instead of
> one. The `type=both` SAML-callback gap is a real product limitation worth a separate fix later.
>
> **Revision 2026-06-05 (c):** A pre-existing authentication-bypass was surfaced by the new
> `ScimDeactivationAuthIT` and fixed (TDD): locked/deactivated accounts could still log in because
> `UserDatabaseLoginModule` (and the remember-me `CookieAuthenticationLoginModule`) never checked
> `UserProfile.isLocked()`. Both now fail closed. See the auth-lock memory note.

## Goal

Close the integration-test gaps around federated identity:

1. **Approach A — extend the existing SCIM ITs (default gate).** `wikantik-it-test-rest` already
   exercises the `/scim/v2/*` provisioning/deprovisioning/group loop over the wire. Two genuine gaps
   remain: (a) no assertion that a *deactivated* user can no longer authenticate, and (b) no replay of
   real Okta/Entra vendor sample payloads. Add both to the existing module.
2. **Approach 2 — Keycloak SSO (default gate).** Replace both SSO protocol mocks
   (`mock-oauth2-server` for OIDC, SimpleSAMLphp for SAML) with a single Keycloak container serving
   both protocols, without losing any existing coverage.
3. **Approach B — Authentik full-loop SCIM (opt-in profile).** A real IdP (Authentik) configured as a
   SCIM client that pushes provisioning to the wiki, asserting the end-to-end create / disable /
   group-membership loop. Heavy and async; runs only under an opt-in Maven profile, never on the
   per-commit gate.

All three ship. The user explicitly chose: extend the existing SCIM ITs (no new module); replace both
SSO mocks with Keycloak; put Authentik in a separate opt-in profile; and give `bin/run-tests.sh` a
cleaner CLI, a real `--help`, and a build-output routing parameter.

## Current state (verified 2026-06-05)

IT modules under `wikantik-it-tests/`:

- `wikantik-it-test-rest` — REST API IT. **Already contains the SCIM wire-level suite:**
  - `ScimUsersIT` (495 lines): auth-401 (no/bad token), create→201+`active:true`+`meta.location`,
    list+filter, deactivate (`PATCH active:false`) + audit event, reactivate, soft-`DELETE`→204 with
    row retained + `active:false`, discovery, unsupported-filter rejection.
  - `ScimGroupsIT` (706 lines): auth-401, member provisioning, group create→201, GET, PATCH add
    member, PATCH remove member (value-path), PUT replace, filter by `displayName`, and the
    **admin-role invariant** (Step 9): a SCIM group named `Admin` with a member is created, then a
    direct `SELECT count(*) FROM roles WHERE login_name=? AND role='Admin'` asserts the member got
    **no** Admin role.
- `wikantik-it-test-sso` — OIDC end-to-end against `ghcr.io/navikt/mock-oauth2-server`. Covers the
  happy-path auto-provision + login/logout (`SSOLoginIT`) and fail-closed edge cases (`SSOEdgeCaseIT`):
  direct callback with no `code`, forged `state`, garbage `code`, and session-ID rotation across login.
- `wikantik-it-test-sso-saml` — SAML end-to-end against `kristophjunge/test-saml-idp:1.15`
  (SimpleSAMLphp). One load-bearing assertion: the `uid=['1']` multi-valued SAML attribute is
  unwrapped to the scalar login name `"1"` via `firstScalar()`.
- `wikantik-it-test-custom-jdbc` — Selenide browser suite (uses the shared `wikantik-selenide-tests`
  jar).

Genuine gaps confirmed by inspection:

- **SCIM:** no test logs in *as* a deactivated user to prove authentication is rejected; no real
  Okta/Entra sample payloads are replayed. Everything else in the spec's Approach-A matrix already
  exists.
- **SSO/SCIM-fullloop:** no Keycloak and no Authentik anywhere in the repo. Those gaps are real.

Key facts that shaped the design:

- **SSO never consumes group/role claims.** `SSOLoginModule` / `SSOAutoProvisionService` map only
  identity + claim-mapping (`loginName` / `fullName` / `email`). Groups and roles flow **exclusively
  through SCIM**. Therefore Keycloak's value is *real OIDC + SAML protocol conformance*, not
  "realistic group claims."
- **`ScimGroupResource` holds an Admin-never-granted invariant** (already asserted by `ScimGroupsIT`
  Step 9): it only touches `GroupManager` + `UserDatabase`, never the `roles` table.
- **IT container pattern in this repo:** `docker-maven-plugin` images + `build-helper`
  reserve-network-port + per-module container names + a stale-container cleanup `exec` step. **Not**
  Testcontainers. New container wiring follows this exactly.
- `bin/run-tests.sh` already implements the correct phased engine: a parallel unit reactor (Phase 1,
  installs artifacts incl. the WAR) followed by per-module IT builds with `-pl <module>` and no `-am`
  (Phase 2), a flock guard, zombie-JVM cleanup, and an aggregated report. This work *extends* it, not
  rewrites it.

## Module map (after)

| Module | Before | After | Gate |
|---|---|---|---|
| `wikantik-it-test-rest` | REST API IT (incl. SCIM suite) | + deactivated-cannot-auth test + Okta/Entra fixture replay | default |
| `wikantik-it-test-sso` | OIDC via `mock-oauth2-server` | OIDC + SAML via one Keycloak; edge cases folded in | default |
| `wikantik-it-test-sso-saml` | SAML via SimpleSAMLphp | **deleted** (folded into `-sso`) | — |
| `wikantik-it-test-custom-jdbc` | Selenide browser suite | unchanged | default |
| `wikantik-it-test-scim-fullloop` | — | **NEW** — Approach B, Authentik → wiki SCIM push | opt-in profile `scim-fullloop` |

No new `wikantik-it-test-scim` module — SCIM stays inside `wikantik-it-test-rest`.
Default gate after: `rest`, `sso`, `custom-jdbc`.

## Approach A — extend the existing SCIM ITs (default gate)

All work lands in `wikantik-it-test-rest`. No new module, no migration of the 1,200 existing lines.

**Gap 1 — deactivated user cannot authenticate.** Add a test to `ScimUsersIT` that:
1. provisions a user via `POST /scim/v2/Users` with a known password (or via the existing seed path
   if SCIM-provisioned users have no password — see open question below);
2. confirms the user can authenticate (`POST /api/auth/login` → 200), establishing a baseline;
3. deactivates via `PATCH active:false`;
4. asserts a fresh `POST /api/auth/login` for that user is now rejected (401/403).

   *Open implementation question for the plan:* SCIM-provisioned users may not have a local password
   set. If so, the baseline-login step is impossible and the test instead provisions through the path
   that does set a credential, or asserts deactivation via an equivalent authenticated-action probe.
   The plan resolves this by inspecting `ScimUserResource` / `UserLifecycleService` before writing the
   test, and picks whichever credential path actually exists.

**Gap 2 — real Okta/Entra sample payloads.** Commit vendor SCIM request bodies as JSON test resources
under `wikantik-it-test-rest/src/test/resources/scim-samples/` (e.g. `okta-create-user.json`,
`okta-deactivate-patch.json`, `entra-create-user.json`, `entra-group-patch.json`), sourced from the
vendors' published SCIM provisioning docs. Add a test (parameterized over the sample files) that
replays each against the live endpoint and asserts the wiki accepts the real-world shape (Okta's
`replace`-op PATCH form, Entra's `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`
extension), not merely an idealized hand-rolled payload.

## Approach 2 — Keycloak SSO replacement (default gate)

One Keycloak container, one realm with two clients (an OIDC client and a SAML client). The
`wikantik-it-test-sso` module boots Keycloak and runs both flows; `wikantik-it-test-sso-saml` and both
old mock images are retired.

**Sequencing protects coverage — nothing is deleted before its replacement is green:**

1. Stand up Keycloak with the **OIDC** client. Make `SSOLoginIT` + `SSOEdgeCaseIT` (forged state,
   garbage code, no-code, session rotation) green against it. **Then** delete `mock-oauth2-server`.
2. Add the **SAML** client to the same realm. Configure a Keycloak user attribute emitted as a
   **multi-valued** SAML attribute so the `uid=['1']` → `"1"` `firstScalar()` assertion is preserved.
   Port `SAMLLoginIT`, prove that exact assertion green. **Then** delete `wikantik-it-test-sso-saml` +
   SimpleSAMLphp.

**Documented fallback (K-OIDC-only):** if step 2's multi-valued SAML attribute proves intractable in
Keycloak, Keycloak replaces only `mock-oauth2-server` and SimpleSAMLphp stays in
`wikantik-it-test-sso-saml`. Zero coverage loss; two IdP images remain. "Replace both" is the target,
not a mandate to delete before green.

**Wiring:** `docker-maven-plugin` Keycloak image + `build-helper` reserved port + per-module container
name + stale-container cleanup exec, matching the existing SSO module pattern. Keycloak realm imported
from a committed realm-export JSON (clients, the test user, the multi-valued attribute mapper).

## Approach B — `wikantik-it-test-scim-fullloop` (opt-in profile `scim-fullloop`)

A new Maven profile `scim-fullloop` is added to `wikantik-it-tests/pom.xml`; the
`wikantik-it-test-scim-fullloop` module is built and run **only** when that profile is active, so the
default `integration-tests` gate never pays for it.

Flow: boot Authentik + pgvector + Cargo Tomcat → configure an Authentik **SCIM provider** targeting
the wiki's `/scim/v2` + bearer token → via Authentik's API create a user and group → **poll the wiki**
(bounded, modeled on `RestSeedHelper.awaitAdminReady`, to absorb async propagation) until provisioned
→ **disable** the user in Authentik → poll → assert deactivated (cannot authenticate) → membership
change → assert reflected.

**Container footprint (the reason it is opt-in):** Authentik requires its own Postgres + Redis +
server (and worker) alongside the wiki's pgvector and Cargo Tomcat. That footprint plus async-sync
timing is why this never runs on the per-commit gate. If the footprint becomes painful, Zitadel is a
lighter SCIM-client alternative — noted here, but we build Authentik as chosen.

## `bin/run-tests.sh` changes

Keep the phased engine. Surface changes:

- **Default IT list** becomes `rest sso custom-jdbc` (drop `sso-saml`, folded into `sso`). SCIM
  continues to run inside `rest` — no separate module to register.
- **`--module <name>`** accepts `rest|sso|custom-jdbc|scim-fullloop`.
- **`--fullloop`** new mode: runs `wikantik-it-test-scim-fullloop` with
  `-Pintegration-tests,scim-fullloop`. Excluded from `--it` and from `--parallel` (heavy, opt-in).
- **`--list`** prints modules and the gate each belongs to, then exits.
- **`--output MODE` / `-o MODE`** (new) controls where Maven build output goes:
  - `file` (default) — build output to the per-step log file only; console shows the PASS/FAIL summary
    (current behavior).
  - `console` — stream build output straight to stdout/err; no log file written.
  - `both` — `tee` build output to the log file **and** stdout/err.
  - Implementation subtlety: under `console`/`both` the `mvn` exit status must be read from
    `${PIPESTATUS[0]}` (not `tee`) so PASS/FAIL detection stays correct. `pipefail` is already set.
- **Replace the `sed -n '2,32p' "$0"` help hack** with a real `usage()` heredoc:

```
Usage: bin/run-tests.sh [MODE] [OPTIONS]

MODES (default: full suite = unit, then default-gate IT modules)
  --unit                 Unit reactor only (Phase 1)
  --it                   Default-gate IT modules only (assumes --unit ran)
  --module <name>        One IT module: rest|sso|custom-jdbc|scim-fullloop
  --fullloop             Opt-in Authentik SCIM full-loop (-Pscim-fullloop)
  --list                 Show modules and their gate, then exit

OPTIONS
  --parallel N, -p N     Run default-gate IT modules in one -T N reactor
  --output MODE, -o MODE Build output routing: file (default) | console | both
  --help, -h             This help

ENVIRONMENT
  UNIT_PARALLELISM=1C    Unit-phase -T value
  IT_PARALLELISM=1       Fallback for --parallel (flag wins)

EXAMPLES
  bin/run-tests.sh                      # full deterministic gate (unit + rest,sso,custom-jdbc)
  bin/run-tests.sh --unit               # fast unit-only pass
  bin/run-tests.sh --module rest        # REST + SCIM IT only
  bin/run-tests.sh --it --parallel 4    # default IT modules, 4-way
  bin/run-tests.sh --module sso -o both # Keycloak SSO IT, output to log AND console
  bin/run-tests.sh --fullloop           # opt-in Authentik full-loop (heavy)

EXIT: 0 only if every phase/module that ran reached BUILD SUCCESS.
```

## Build sequence (implementation order)

1. **Approach A** — extend `ScimUsersIT` (deactivated-cannot-auth) + add Okta/Entra fixture-replay
   test in `wikantik-it-test-rest`. Deterministic, no new container tech; immediate gate value.
2. **Keycloak OIDC** — replace `mock-oauth2-server`, green, delete it.
3. **Keycloak SAML** — multi-valued attribute, green, delete `sso-saml` + SimpleSAMLphp (or fall back
   to K-OIDC-only).
4. **`run-tests.sh`** — drop `sso-saml` from the default list, add `--output`, `--fullloop`, `--list`,
   rewrite `--help`.
5. **Approach B** — `wikantik-it-test-scim-fullloop` + `scim-fullloop` profile (Authentik).

Each step lands green before the next; no mock is deleted until its Keycloak replacement is proven.

## Testing approach

- TDD throughout, per repo convention: each new IT asserts a real behavior and fails before the wiring
  exists.
- New container wiring mirrors the existing IT pattern (`docker-maven-plugin` + `build-helper` ports +
  Cargo + pgvector + stale-container cleanup) so modules compose with `--parallel` and the
  dynamic-port scheme.
- The full gate (`bin/run-tests.sh`, default) must be green before any production-code commit, per the
  repo's "full IT reactor before committing" rule.
- Approach B is validated under `bin/run-tests.sh --fullloop` and is **not** part of the green-gate
  requirement.

## Out of scope

- Production-code changes to SSO or SCIM behavior (this is test coverage; bugs found are filed/fixed
  separately).
- A standalone `wikantik-it-test-scim` module (SCIM coverage already lives in `wikantik-it-test-rest`).
- Microsoft Entra / Okta live-tenant testing (manual staging conformance, not CI).
- Zitadel / Dex modules (mentioned only as alternatives).
