# SSO Hardening Test Suite — Design

**Date:** 2026-05-24
**Status:** Approved, pre-implementation
**Subsystem:** Authentication — SSO (`com.wikantik.auth.sso`, pac4j OIDC/SAML)

## Problem

SSO has solid unit coverage of the happy paths and one end-to-end OIDC
integration test (`SSOLoginIT`), but the edge cases that matter for a
security-sensitive auth feature are untested:

- **SAML has zero integration coverage.** `SSOConfig` builds a
  `SAML2Client`, unit tests mock it, but no test exercises a real SAML
  login flow. `mock-oauth2-server` (the OIDC IT's IdP) cannot speak SAML.
- **Identity binding is untested and looks unsafe.** The login module binds
  a `WikiPrincipal` from a mutable, possibly non-unique login claim with no
  tie to the immutable `sub`.
- **Claim handling, provisioning races, callback security, and session
  fixation** are all unverified.

This effort closes those gaps with a test pyramid and fixes the production
defects the tests expose, following the repo's TDD ethos (test red first,
then fix to green).

## Goals

1. A deterministic unit/component layer pinning the identity-binding,
   claim-handling, and provisioning edge cases.
2. A full SAML browser integration test against a live SAML IdP container.
3. Integration coverage of callback-security and session edges for OIDC.
4. Production fixes for every defect a test proves, landed test-first.

## Non-goals

- Token refresh / long-lived session re-authentication flows.
- Performance/load testing of the SSO path.
- Multi-tenant / multi-engine SSO config isolation.
- A `both`-mode (OIDC + SAML in one module) browser IT — `client_name`
  selection is covered at the servlet-unit level instead.

## Strategy

A pyramid anchored on the seams the code already exposes:

| Layer | Location | Scope |
|-------|----------|-------|
| Unit / component | `wikantik-main/src/test/java/com/wikantik/auth/sso/` | Identity binding, claim handling, provisioning, config, client selection |
| Integration (OIDC) | `wikantik-it-tests/wikantik-it-test-sso/` (extend) | Callback security, session fixation, account-collision end-to-end |
| Integration (SAML) | `wikantik-it-tests/wikantik-it-test-sso-saml/` (new) | Full SAML browser login + auto-provision + logout + multi-valued attrs |

**TDD discipline.** Every test targeting a suspected defect lands **red
first** against current code, proving the defect, then production code is
fixed to green — one defect at a time. Tests that confirm already-correct
behavior land green.

## Latent-bug catalog

Each hypothesis below was extracted from reading the implementation. Each
becomes one or more tests; the ones confirmed as defects drive a production
fix.

### Identity binding (headline, security-relevant)

1. **Account takeover by login-name coincidence.**
   `SSOLoginModule.login()` adds `WikiPrincipal(resolvedLoginName)` and
   `SSOAutoProvisionService.provisionIfNeeded()` returns early if a profile
   with that name already exists. An IdP asserting `preferred_username=admin`
   therefore binds to the pre-existing local `admin` account with no verified
   link. **Test:** SSO assertion whose login claim equals an existing local
   (non-SSO) account → the resulting session must not silently inherit that
   account's identity.
2. **Mutable-claim reassignment.** Identity keys on
   `preferred_username`/`email` (mutable at the IdP), never on the immutable
   `sub`. Reassigning a username at the IdP transfers the wiki account and its
   history to a different human. **Test:** two profiles, same login claim,
   different `sub`.

### Claim handling

3. **Multi-valued claims stringified.** `resolveLoginName` and
   `resolveAttribute` call `.toString()` on the attribute value. pac4j SAML
   attributes are always `List`s, so e.g. email becomes `"[a@b.com]"`. **Test:**
   unit tests with `List`-valued claims; the SAML IT surfaces it end-to-end.
4. **Login-name safety.** No validation of the resolved login name:
   whitespace, control characters, empty-after-trim, over-long values, or
   reserved names flow straight into `WikiPrincipal` and the user store.
   **Test:** parameterised hostile claim values.

### Provisioning

5. **Check-then-create race.** `findByLoginName` → catch
   `NoSuchPrincipalException` → `newProfile` + `save` is not atomic. Concurrent
   first-logins double-provision or hit a swallowed constraint error. **Test:**
   concurrent `provisionIfNeeded` against a real `TestEngine` `UserDatabase`.
6. **Auto-provision disabled + new user.** With `autoProvision=false` and no
   local profile, `login()` still returns true and adds the principal — a
   half-authenticated state with no backing profile. **Test:** documents the
   behavior; decision recorded on whether it is a defect.

### Config / multi-client

7. **SAML init failure degrades silently.** `buildSamlClient` catches all
   exceptions and logs; an empty client list turns `/sso/login` into a generic
   `no_sso_client` redirect rather than a visible misconfiguration. **Test:**
   asserts the degradation is observable (logged + distinguishable response).
8. **`client_name` selection in `both` mode** is untested — both the default
   client choice and explicit `?client_name=SAML2Client` selection. **Test:**
   servlet-unit level against a `Config` with two clients.

### Callback / session security (integration)

9. **Forged / missing state and replayed code.** Only missing-`code` is
   covered today. **Test:** forged state, replayed (already-consumed) code,
   mismatched session — each must fail closed to `/login?error=...`, never 500.
10. **Session fixation.** **Test:** assert `JSESSIONID` rotates across the
    callback so a pre-fixed session cannot ride the authenticated one.

## Test artifacts

### Unit / component (new in `wikantik-main`)

- **`SSOLoginModuleEdgeCasesTest`** — #1 principal binding to an existing
  account, #3 multi-valued login claim, #4 login-name safety, #6
  disabled-provision half-state, null/blank fallback chain.
- **`SSOAutoProvisionServiceEdgeCasesTest`** — #3 multi-valued email/fullName,
  #5 concurrency race, existing-account collision behavior.
- **`SSOConfigSamlTest`** (or extend `SSOConfigCITest`) — #7 SAML client
  construction with an auto-generated keystore, `both`-mode client ordering,
  missing-props degradation.
- **`SSORedirectServletClientSelectionTest`** — #8 `client_name` selection
  among multiple configured clients.

### Integration — extend OIDC module (`SSOEdgeCaseIT`)

In `wikantik-it-test-sso`, a new `SSOEdgeCaseIT` covering #9 (forged state /
replayed code variants), #10 (session-fixation `JSESSIONID` rotation), #1
(account-takeover end-to-end: SSO login as an existing local account name),
and re-login idempotency (a second SSO login does not duplicate the profile).

### Integration — new `wikantik-it-test-sso-saml` module

Mirrors the OIDC module's pom shape:

- Fixed host port (no parallel IT modules under the `integration-tests`
  profile).
- `docker-maven-plugin` starts PostgreSQL + a SAML IdP container
  (SimpleSAMLphp-based `test-saml-idp`) in `pre-integration-test`, stops them
  in `post-integration-test`.
- Cargo deploys the WAR; the module's own `wikantik-custom.properties` sets
  `wikantik.sso.type=saml` with the IdP metadata URL, SP entity id, and SP
  ACS URL. pac4j auto-generates the SP keystore on first boot; the IdP
  registers wikantik's SP entity-id/ACS via container env vars.
- failsafe includes only `**/SAMLLoginIT.java`.
- **`SAMLLoginIT`** drives one happy-path SAML login → auto-provision →
  assert authenticated `UserBadge` → logout, plus an assertion that a
  multi-valued SAML attribute (email) maps to a clean scalar (proving the #3
  fix end-to-end).

## Production fixes (driven test-first)

The fixes below land only after a red test proves the defect.

- **#1 / #2 — `sub`-based identity binding (headline).** Introduce an
  `wikantik.sso.identityClaim` configuration defaulting to `sub` so the wiki
  identity is keyed on the IdP's immutable subject, with a documented opt-in
  to trust a mutable claim (`preferred_username`/`email`) for deployments that
  want today's behavior. Provisioning and principal binding key on the stable
  identity; binding to a pre-existing local account requires a verified link
  rather than a bare name match. This touches the security model, so the
  concrete approach is surfaced for review before landing (may need a
  migration and a config knob).
- **#3 — multi-valued claims.** Normalise list-valued attributes to their
  first scalar element in `resolveLoginName`/`resolveAttribute`.
- **#4 — login-name safety.** Validate/reject hostile resolved login names
  (trim, length bound, reserved-name and control-character rejection) with a
  `FailedLoginException` carrying the reason.
- **#5 — provisioning race.** Make provisioning tolerant of a concurrent
  create (catch the duplicate and re-resolve, rather than logging and
  swallowing).
- **#6, #7** — resolved per the test outcome (behavior change vs. documented
  intent).

## Risks

- **SAML IdP container is the riskiest piece.** SP/IdP metadata trust
  handshake and clock skew are classic flake sources. Fallback if it proves
  brittle: a component-level SAML test using pac4j SAML fixtures — but that is
  a downgrade from the agreed full IT, so it is raised for decision rather
  than taken silently.
- **`both`-mode `client_name`** stays at servlet-unit level to avoid standing
  up two IdP containers in one module; promote to an IT only if desired.
- **#1/#2 fix depth.** The identity-binding change is the deepest, may need a
  schema migration and a config knob, and alters the security model — its
  approach is reviewed before it lands.

## Out of scope / deferred

- Token refresh and session-timeout re-authentication.
- Load/performance testing of the SSO path.
- Multi-engine SSO config isolation.
