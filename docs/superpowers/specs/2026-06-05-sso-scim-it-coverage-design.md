# SSO + SCIM Integration-Test Coverage Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan
**Scope:** Integration-test infrastructure only. No production-code behavior change is required; this
work adds end-to-end coverage and modernizes the SSO IdP container, and may surface (but does not
plan) production bugs.

## Goal

Close the integration-test gaps around federated identity:

1. **Approach A — SCIM-direct IT (default gate).** The `wikantik-scim` module today has *unit tests
   only*; nothing exercises the `/scim/v2/*` provisioning/deprovisioning/group loop over the wire.
   Add a deterministic REST-driven IT.
2. **Approach 2 — Keycloak SSO (default gate).** Replace both SSO protocol mocks
   (`mock-oauth2-server` for OIDC, SimpleSAMLphp for SAML) with a single Keycloak container serving
   both protocols, without losing any existing coverage.
3. **Approach B — Authentik full-loop SCIM (opt-in profile).** A real IdP (Authentik) configured as a
   SCIM client that pushes provisioning to the wiki, asserting the end-to-end create / disable /
   group-membership loop. Heavy and async; runs only under an opt-in Maven profile, never on the
   per-commit gate.

All three ship. The user explicitly chose: replace both SSO mocks with Keycloak; put Authentik in a
separate opt-in profile; and give `bin/run-tests.sh` a cleaner CLI, a real `--help`, and a
build-output routing parameter.

## Current state (verified 2026-06-05)

IT modules under `wikantik-it-tests/`:

- `wikantik-it-test-rest` — REST API IT.
- `wikantik-it-test-sso` — OIDC end-to-end against `ghcr.io/navikt/mock-oauth2-server`. Covers the
  happy-path auto-provision + login/logout (`SSOLoginIT`) and fail-closed edge cases
  (`SSOEdgeCaseIT`): direct callback with no `code`, forged `state`, garbage `code`, and session-ID
  rotation across login.
- `wikantik-it-test-sso-saml` — SAML end-to-end against `kristophjunge/test-saml-idp:1.15`
  (SimpleSAMLphp). One load-bearing assertion: the `uid=['1']` multi-valued SAML attribute is
  unwrapped to the scalar login name `"1"` via `firstScalar()`.
- `wikantik-it-test-custom-jdbc` — Selenide browser suite (uses the shared
  `wikantik-selenide-tests` jar).

Key facts that shaped the design:

- **SSO never consumes group/role claims.** `SSOLoginModule` / `SSOAutoProvisionService` map only
  identity + claim-mapping (`loginName` / `fullName` / `email`). Groups and roles flow **exclusively
  through SCIM**. Therefore Keycloak's value is *real OIDC + SAML protocol conformance*, not
  "realistic group claims."
- **`ScimGroupResource` holds an Admin-never-granted invariant:** it only touches `GroupManager` +
  `UserDatabase`, never the `roles` table. A SCIM group named `Admin` creates a group, not a role
  grant. This invariant must be asserted end-to-end.
- **IT container pattern in this repo:** `docker-maven-plugin` images + `build-helper`
  reserve-network-port + per-module container names + a stale-container cleanup `exec` step. **Not**
  Testcontainers. New modules follow this exactly.
- `bin/run-tests.sh` already implements the correct phased engine: a parallel unit reactor (Phase 1,
  installs artifacts incl. the WAR) followed by per-module IT builds with `-pl <module>` and no
  `-am` (Phase 2), a flock guard, zombie-JVM cleanup, and an aggregated report. This work *extends*
  it, not rewrites it.

## Module map (after)

| Module | Before | After | Gate |
|---|---|---|---|
| `wikantik-it-test-rest` | REST API IT | unchanged | default |
| `wikantik-it-test-sso` | OIDC via `mock-oauth2-server` | OIDC + SAML via one Keycloak; edge cases folded in | default |
| `wikantik-it-test-sso-saml` | SAML via SimpleSAMLphp | **deleted** (folded into `-sso`) | — |
| `wikantik-it-test-custom-jdbc` | Selenide browser suite | unchanged | default |
| `wikantik-it-test-scim` | — | **NEW** — Approach A, REST-driven SCIM lifecycle | default |
| `wikantik-it-test-scim-fullloop` | — | **NEW** — Approach B, Authentik → wiki SCIM push | opt-in profile `scim-fullloop` |

Default gate after: `rest`, `sso`, `scim`, `custom-jdbc`.

## Approach A — `wikantik-it-test-scim` (default gate)

**No IdP container.** pgvector + Cargo Tomcat with `wikantik.scim.token` set, driven by RestAssured
(already used by `-rest`). Pure wire-level exercise of the SCIM service-provider.

Test matrix:

- **Provisioning lifecycle:** `POST /Users` → 201; `GET /Users/{id}` → 200; `PATCH /Users/{id}`
  `active:false` → assert the user can no longer authenticate (REST login → 401/403); soft
  `DELETE /Users/{id}`.
- **Group lifecycle:** `POST /Groups`; membership `PATCH add/remove`; assert membership reflected via
  `GroupManager` (e.g. through an admin/group read path).
- **Admin-never-granted invariant:** provision a user into a SCIM group literally named `Admin`, then
  assert the user receives **no** `AllPermission` and is rejected at `/admin/*`. Highest-value
  assertion in the design.
- **Auth:** missing/invalid bearer token → 401.
- **Fixtures ("samples"):** real **Okta** and **Entra** SCIM request bodies committed as JSON test
  resources and replayed, so the test exercises their actual PATCH-op shapes and enterprise-extension
  schema, not an idealized payload. Source the bodies from the vendors' published SCIM provisioning
  docs.

## Approach 2 — Keycloak SSO replacement (default gate)

One Keycloak container, one realm with two clients (an OIDC client and a SAML client). The
`wikantik-it-test-sso` module boots Keycloak and runs both flows; `wikantik-it-test-sso-saml` and both
old mock images are retired.

**Sequencing protects coverage — nothing is deleted before its replacement is green:**

1. Stand up Keycloak with the **OIDC** client. Make `SSOLoginIT` + `SSOEdgeCaseIT` (forged state,
   garbage code, no-code, session rotation) green against it. **Then** delete `mock-oauth2-server`.
2. Add the **SAML** client to the same realm. Configure a Keycloak user attribute emitted as a
   **multi-valued** SAML attribute so the `uid=['1']` → `"1"` `firstScalar()` assertion is preserved.
   Port `SAMLLoginIT`, prove that exact assertion green. **Then** delete `wikantik-it-test-sso-saml`
   + SimpleSAMLphp.

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

- **Default IT list** becomes `rest sso scim custom-jdbc` (drop `sso-saml`, add `scim`).
- **`--module <name>`** accepts `rest|sso|scim|custom-jdbc|scim-fullloop`.
- **`--fullloop`** new mode: runs `wikantik-it-test-scim-fullloop` with
  `-Pintegration-tests,scim-fullloop`. Excluded from `--it` and from `--parallel` (heavy, opt-in).
- **`--list`** prints modules and the gate each belongs to, then exits.
- **`--output MODE` / `-o MODE`** (new) controls where Maven build output goes:
  - `file` (default) — build output to the per-step log file only; console shows the PASS/FAIL
    summary (current behavior).
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
  --module <name>        One IT module: rest|sso|scim|custom-jdbc|scim-fullloop
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
  bin/run-tests.sh                      # full deterministic gate (unit + rest,sso,scim,custom-jdbc)
  bin/run-tests.sh --unit               # fast unit-only pass
  bin/run-tests.sh --module scim        # just the new SCIM-direct IT
  bin/run-tests.sh --it --parallel 4    # default IT modules, 4-way
  bin/run-tests.sh --module sso -o both # Keycloak SSO IT, output to log AND console
  bin/run-tests.sh --fullloop           # opt-in Authentik full-loop (heavy)

EXIT: 0 only if every phase/module that ran reached BUILD SUCCESS.
```

## Build sequence (implementation order)

1. **Approach A** — `wikantik-it-test-scim` (deterministic, no new container tech; immediate gate
   value).
2. **Keycloak OIDC** — replace `mock-oauth2-server`, green, delete it.
3. **Keycloak SAML** — multi-valued attribute, green, delete `sso-saml` + SimpleSAMLphp (or fall back
   to K-OIDC-only).
4. **`run-tests.sh`** — wire `scim` into the default list, add `--output`, `--fullloop`, `--list`,
   rewrite `--help`.
5. **Approach B** — `wikantik-it-test-scim-fullloop` + `scim-fullloop` profile (Authentik).

Each step lands green before the next; no mock is deleted until its Keycloak replacement is proven.

## Testing approach

- TDD throughout, per repo convention: each new IT asserts a real behavior and fails before the wiring
  exists.
- New modules mirror the existing IT pattern (`docker-maven-plugin` + `build-helper` ports + Cargo +
  pgvector + stale-container cleanup) so they compose with `--parallel` and the dynamic-port scheme.
- The full gate (`bin/run-tests.sh`, default) must be green before any production-code commit, per
  the repo's "full IT reactor before committing" rule.
- Approach B is validated under `bin/run-tests.sh --fullloop` and is **not** part of the green-gate
  requirement.

## Out of scope

- Production-code changes to SSO or SCIM behavior (this is test coverage; bugs found are filed/fixed
  separately).
- Microsoft Entra / Okta live-tenant testing (manual staging conformance, not CI).
- Zitadel / Dex modules (mentioned only as alternatives).
