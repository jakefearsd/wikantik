# Single Sign-On (SSO) Configuration

Wikantik supports Single Sign-On via [pac4j](https://www.pac4j.org/) as an
OpenID Connect (OIDC) and/or SAML 2.0 service provider. This is the
**authoritative operator reference** for configuring SSO. The implementation
lives in `wikantik-main/src/main/java/com/wikantik/auth/sso/` (`SSOConfig`,
`SSOLoginModule`, `SSOCallbackServlet`, `SSORedirectServlet`,
`SSOAutoProvisionService`). The historical design notes in
[OAuthImplementation.md](OAuthImplementation.md) and [FullOAuth.md](FullOAuth.md)
are superseded and kept only for context.

**`SSORedirectServlet` (`/sso/login`)** is the entry point of the SSO flow. It
locates the appropriate pac4j client (selected by an optional `client_name`
query parameter; falls back to the first configured client), requests a
redirection action from it, and sends the browser to the Identity Provider. If
SSO is disabled or unconfigured it responds 404; configuration errors respond
500; any other failure redirects to `/login?error=sso_redirect_failed`.

SSO coexists with traditional username/password login — it is purely additive.
When enabled, the SPA login page shows a provider button alongside the local
login form.

> **Related — IdP provisioning:** SSO is the *login* path: it authenticates
> users and auto-provisions local profiles on first login. If your IdP also
> manages group membership or needs to create/deactivate accounts out-of-band,
> see [ScimProvisioning.md](ScimProvisioning.md) — the SCIM 2.0 server at
> `/scim/v2/*` covers the IdP *provisioning* path and complements SSO.

## Quick start (Google OIDC)

In `wikantik-custom.properties`:

```properties
wikantik.baseURL = https://wiki.example.com/
wikantik.sso.enabled = true
wikantik.sso.type = oidc
wikantik.sso.oidc.discoveryUri = https://accounts.google.com/.well-known/openid-configuration
wikantik.sso.oidc.clientId = <client-id>.apps.googleusercontent.com
wikantik.sso.oidc.clientSecret = <client-secret>
wikantik.sso.oidc.scope = openid profile email
# Google sends no preferred_username, so key the login name on email.
wikantik.sso.claimMapping.loginName = email
```

Register the **redirect URI** `wikantik.baseURL + /sso/callback`
(e.g. `https://wiki.example.com/sso/callback`) with the provider. It must
exactly match — for a single configured client Wikantik deliberately uses a
clean, parameter-free callback URL (no `?client_name=` suffix), because strict
IdPs such as Google reject any query string on the `redirect_uri`.

> **`wikantik.baseURL` must be set to the public HTTPS origin.** The callback
> URL is derived from it. If it is unset, callbacks fall back to a relative URL
> and OIDC `redirect_uri` validation will fail.

## Property reference

All properties live in `wikantik-custom.properties` (or
`ini/wikantik.properties`). Defaults are applied by `SSOConfig`.

### Core

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.sso.enabled` | `false` | Master switch. When `false`, no SSO clients are built. |
| `wikantik.sso.type` | `oidc` | `oidc`, `saml`, or `both`. |
| `wikantik.sso.autoProvision` | `true` | Create a local profile on first successful SSO login. |
| `wikantik.sso.identityClaim` | `sub` | IdP claim treated as the immutable account-link key. See [Identity binding](#identity-binding-and-account-linking). |
| `wikantik.baseURL` | — | Public HTTPS origin; the `/sso/callback` URL is derived from it. Required. |

### OIDC

Required when `type` is `oidc` or `both`. If any required property is missing,
the OIDC client is skipped and an error is logged (SSO does not fail closed —
other configured clients still load).

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.sso.oidc.discoveryUri` | — | Provider OpenID configuration document URL. **Required.** |
| `wikantik.sso.oidc.clientId` | — | OAuth client ID from the provider console. **Required.** |
| `wikantik.sso.oidc.clientSecret` | — | OAuth client secret (sensitive — keep out of git). **Required.** |
| `wikantik.sso.oidc.scope` | `openid profile email` | Space-delimited scopes. |

Client authentication is pinned to `client_secret_basic` to keep pac4j from
auto-selecting `private_key_jwt`.

### SAML 2.0

Required when `type` is `saml` or `both`. If `identityProviderMetadataPath` or
`serviceProviderEntityId` is missing, the SAML client is skipped and an error
is logged.

| Property | Default | Notes |
|----------|---------|-------|
| `wikantik.sso.saml.identityProviderMetadataPath` | — | Path/URL to the IdP metadata XML. **Required.** |
| `wikantik.sso.saml.serviceProviderEntityId` | — | SP entity ID. **Required.** |
| `wikantik.sso.saml.keystorePath` | — | JKS keystore for SP signing/encryption keys. |
| `wikantik.sso.saml.keystorePassword` | — | Keystore password (sensitive). |
| `wikantik.sso.saml.privateKeyPassword` | — | Private-key password (sensitive). |
| `wikantik.sso.saml.serviceProviderMetadataPath` | — | Optional writable path where pac4j persists generated SP metadata (incl. the registered ACS URL). Without it pac4j holds SP metadata only in memory, which can break ACS-URL resolution against some IdPs. |
| `wikantik.sso.saml.authnRequestBindingType` | pac4j default | **Only HTTP-Redirect is supported** (`urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect`). Do not set HTTP-POST — the redirect servlet does not render a form-post AuthnRequest. |

The Assertion Consumer Service (ACS) URL is registered as exactly the
`/sso/callback` URL (no `client_name` query parameter), so the IdP's assertion
`Destination` matches the path it posts back to.

### Claim mapping

IdP claims are mapped to the local profile fields. Defaults suit a standard
OIDC IdP; Google needs `loginName` remapped to `email` (it sends no
`preferred_username`).

| Property | Default | Maps to |
|----------|---------|---------|
| `wikantik.sso.claimMapping.loginName` | `preferred_username` | Wiki login name |
| `wikantik.sso.claimMapping.fullName` | `name` | Display name |
| `wikantik.sso.claimMapping.email` | `email` | Email |

These properties (and `identityClaim`) are bridged into the JAAS options that
`SSOLoginModule` actually reads, by `DefaultAuthenticationManager.initSSOConfig`.
Configure them once under `wikantik.sso.*` — an explicit
`wikantik.loginModule.options.*` still takes precedence if present. (Historically,
omitting this bridge left the configured mappings silently inert, which is how a
Google login ended up keyed on the numeric `sub` instead of email.)

Claim values are sanitised before use: multi-valued claims are normalised to
their first scalar element, and blank / whitespace-only / control-character
login names from the IdP are rejected.

## Identity binding and account linking

SSO links an IdP identity to a local account on the claim named by
`wikantik.sso.identityClaim` (default `sub`, the IdP's immutable subject).
Set it to `preferred_username` **only** to deliberately trust a mutable claim.

- Auto-provisioned profiles are stamped with an `sso.subject` marker attribute.
- **SSO never adopts a pre-existing non-SSO-linked local account of the same
  name.** A name collision against an account without a matching `sso.subject`
  marker **fails closed** (login is refused) rather than silently taking over
  the account.
- Concurrent first-login provisioning is idempotent.

## Security behaviour

- **Session fixation defense.** A successful SSO login rotates the HTTP session.
- **CSRF.** The SAML HTTP-POST `/sso/callback` is exempt from the CSRF
  synchronizer-token filter — the IdP-signed assertion is its own CSRF defense.
  OIDC uses the standard authorization-code flow.
- **Failure handling.** SSO failures redirect to the `/login` SPA route with an
  `?error=<code>` parameter that `LoginPage` surfaces. `/login` is dual-registered
  (in `web.xml` and `SpaRoutingFilter.SPA_EXACT`) so it renders rather than 404s.

## Container / Docker configuration

In Docker deployments, the entrypoint injects SSO properties from
`WIKANTIK_SSO_*` environment variables (set in `.env.prod`, never in git). See
`.env.example` and `docker/entrypoint.sh`.

| Environment variable | Property |
|----------------------|----------|
| `WIKANTIK_SSO_ENABLED` | `wikantik.sso.enabled` |
| `WIKANTIK_SSO_TYPE` | `wikantik.sso.type` |
| `WIKANTIK_SSO_OIDC_DISCOVERY_URI` | `wikantik.sso.oidc.discoveryUri` |
| `WIKANTIK_SSO_OIDC_CLIENT_ID` | `wikantik.sso.oidc.clientId` |
| `WIKANTIK_SSO_OIDC_CLIENT_SECRET` | `wikantik.sso.oidc.clientSecret` |
| `WIKANTIK_SSO_OIDC_SCOPE` | `wikantik.sso.oidc.scope` |
| `WIKANTIK_SSO_IDENTITY_CLAIM` | `wikantik.sso.identityClaim` |
| `WIKANTIK_SSO_AUTO_PROVISION` | `wikantik.sso.autoProvision` |
| `WIKANTIK_SSO_CLAIM_LOGIN_NAME` | `wikantik.sso.claimMapping.loginName` |
| `WIKANTIK_SSO_CLAIM_FULL_NAME` | `wikantik.sso.claimMapping.fullName` |
| `WIKANTIK_SSO_CLAIM_EMAIL` | `wikantik.sso.claimMapping.email` |

The container block is OIDC-oriented: it skips entirely unless
`WIKANTIK_SSO_ENABLED=true`, and the SAML properties are **not** passed through
the entrypoint. For SAML in a container, mount a `wikantik-custom.properties`
fragment with the `wikantik.sso.saml.*` keys, or extend `docker/entrypoint.sh`.

The redirect URI registered with the provider must be
`WIKANTIK_BASE_URL` + `/sso/callback`, so `WIKANTIK_BASE_URL` must be the public
HTTPS origin (Google SSO is live in production behind cloudflared at
`wiki.wikantik.com` via this mechanism).

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `redirect_uri_mismatch` from the IdP | Registered redirect URI ≠ `baseURL` + `/sso/callback`, or `wikantik.baseURL` unset/incorrect. |
| Login succeeds but the wiki login name is the numeric `sub` | `claimMapping.loginName` not mapped to `email` for a provider (e.g. Google) that omits `preferred_username`. |
| SSO login refused for a known user | Name collides with a non-SSO local account lacking the `sso.subject` marker — fail-closed by design. Link or rename the local account. |
| SAML assertion rejected on `Destination` mismatch | ACS URL / `serviceProviderMetadataPath` not aligned with the public callback URL; confirm HTTP-Redirect binding. |
| Mapped claims appear ignored | Pre-bridge configuration; ensure `wikantik.sso.claimMapping.*` are set (they are bridged into the LoginModule automatically). |
| Server starts fine, but every SSO login redirects to `sso_redirect_failed` — and the boot log has a line like `OIDC discovery self-check FAILED for <uri>: ... — the identity provider is UNREACHABLE from this host (check outbound network egress / DNS / TLS)` (or `HTTP <status>` / not-a-discovery-document variants) | `OidcDiscoverySelfCheck` probes the OIDC discovery URL once at startup, on a background thread — it never blocks or fails boot, it only logs. pac4j itself fetches discovery **lazily** on the first `/sso/login` request and caches it, so without this log line a lost egress path (firewall, DNS, proxy) looks like the app started healthy and only breaks when a user actually tries to log in. Fix egress/DNS/TLS to the discovery URL (or correct `wikantik.sso.oidc.discoveryUri`) and restart — a lazy pac4j fetch means a config fix alone, without a restart, may not clear a bad cached state. |
