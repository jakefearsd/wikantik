#!/bin/bash
#
# entrypoint.sh — container runtime bootstrap. Materialises
# wikantik-custom.properties from the surrounding env, runs DB migrations
# (idempotent via schema_migrations), optionally seeds dev users, then
# exec's Catalina to start Tomcat.
#
# This file is not run interactively in normal use — it's the
# Dockerfile's CMD. It is included in the image at /opt/wikantik/entrypoint.sh
# and invoked with whatever arguments the operator passes to the
# container (default: catalina.sh run).
#
# Usage:
#   entrypoint.sh             # bootstrap + start Tomcat in foreground
#   entrypoint.sh --help      # show this help
#   entrypoint.sh /bin/sh     # bypass Tomcat (passes through to exec)
#
# Environment variables:
#   POSTGRES_HOST                PG host (required)
#   POSTGRES_PORT                PG port (default 5432)
#   POSTGRES_DB                  PG database (required)
#   POSTGRES_USER                PG application user (required)
#   POSTGRES_PASSWORD            PG application password (required)
#   WIKANTIK_BASE_URL            external base URL (default http://localhost:8080/)
#   WIKANTIK_PAGE_DIR            page tree mount (default /var/wikantik/pages)
#   WIKANTIK_WORK_DIR            scratch dir (default /var/wikantik/work)
#   WIKANTIK_ATTACHMENT_DIR      attachments dir (default /var/wikantik/pages)
#   WIKANTIK_SEED_DEV_USERS      "true" to ensure the default admin exists
#                                (admin/admin123, must-change-on-first-login)
#                                via bin/db/seed-users.sql (dev only)
#   WIKANTIK_DENSE_BACKEND       dense retrieval backend override:
#                                  inmemory | pgvector | lucene-hnsw (default: ini bundle default = inmemory)
#   WIKANTIK_DENSE_EF_SEARCH     pgvector HNSW ef_search knob (default 100; only used when
#                                WIKANTIK_DENSE_BACKEND=pgvector). The lucene-hnsw backend's
#                                knobs (m / ef_construction / ef_search) use HnswParams defaults
#                                unless set via wikantik.search.dense.lucene.* properties.
#   WIKANTIK_LUCENE_DIRECTORY    Lucene Directory backend: nio | mmap (default: nio)
#   WIKANTIK_VERSIONING_CACHE_SIZE
#                                VersioningFileProvider property cache size (default 100;
#                                set 0 for single-entry, -1 to disable; properties are
#                                tiny so a few thousand entries is cheap)
#   WIKANTIK_MAX_INFLIGHT_REQUESTS
#                                BackpressureFilter permit cap (default 390, which must
#                                stay below Tomcat maxThreads=400; 0 or negative disables
#                                backpressure entirely). Requests over the cap get HTTP
#                                503 + Retry-After:1 instead of queueing for up to 60 s.
#                                /api/health and /metrics bypass.
#                                Metric: wikantik_backpressure.rejected_total
#   WIKANTIK_COOKIE_AUTHENTICATION  "true" to enable remember-me re-auth so users
#                                stay logged in across restarts/timeouts (default false)
#   WIKANTIK_SSO_ENABLED         "true" to enable Single Sign-On (default off)
#   WIKANTIK_SSO_TYPE            oidc | saml | both (default oidc)
#   WIKANTIK_SSO_OIDC_DISCOVERY_URI  provider OIDC discovery doc URL
#   WIKANTIK_SSO_OIDC_CLIENT_ID  OAuth client id
#   WIKANTIK_SSO_OIDC_CLIENT_SECRET  OAuth client secret (sensitive)
#   WIKANTIK_SSO_OIDC_SCOPE      OAuth scope (default "openid profile email")
#   WIKANTIK_SSO_IDENTITY_CLAIM  identity claim (default "sub")
#   WIKANTIK_SSO_AUTO_PROVISION  "true" to auto-create profiles (default true)
#   WIKANTIK_SSO_CLAIM_LOGIN_NAME IdP claim mapped to wiki login name
#                                (default preferred_username; Google sends none,
#                                so set to "email" for Google)
#   WIKANTIK_SSO_CLAIM_FULL_NAME IdP claim mapped to display name (default "name")
#   WIKANTIK_SSO_CLAIM_EMAIL     IdP claim mapped to email (default "email")
#                                redirect_uri = WIKANTIK_BASE_URL + /sso/callback
#   WIKANTIK_GENAI_MODE          GenAI ceiling control: full | embeddings-only | none
#                                (default: ini bundle default = full)
#   WIKANTIK_KNOWLEDGE_ENABLED   "true"/"false" — consumed by wikantik.knowledge.enabled
#                                (default true when unset; the consuming property ships
#                                with the same release)
#   WIKANTIK_EMBEDDING_BASE_URL  dense-retrieval embedding service base URL override, e.g.
#                                http://embedding-sidecar:11434 (default: ini bundle default,
#                                the shared inference host — unreachable from some networks)
#   WIKANTIK_EXTRACTOR_BACKEND   KG entity-extractor backend: ollama | claude | disabled
#                                (default: ini bundle default = ollama). When "claude", also
#                                set ANTHROPIC_API_KEY.
#   ANTHROPIC_API_KEY            Anthropic API key, required when WIKANTIK_EXTRACTOR_BACKEND=
#                                claude. Read directly via System.getenv("ANTHROPIC_API_KEY")
#                                by EntityExtractorFactory — passed straight through as a
#                                container process env var; this script does not render it
#                                into any properties file, so no conditional block exists below.
#   WIKANTIK_SCIM_TOKEN          SCIM bearer token (sensitive). ScimAccessFilter
#                                (wikantik-scim) reads this exclusively via
#                                System.getProperty("wikantik.scim.token") — never through
#                                wikantik-custom.properties/PropertyReader — so this script
#                                injects it as a JVM system property (-D) via CATALINA_OPTS.
#                                Because catalina.sh word-splits CATALINA_OPTS, the token
#                                MUST match [A-Za-z0-9+/=_-] — hex/base64 only, no spaces
#                                or shell metacharacters (generate with: openssl rand -hex 32);
#                                the script refuses to boot (exit 1) on a violating token.
#   WIKANTIK_CONNECTORS_CRYPTO_KEY  base64-encoded 32-byte AES-256 key for the connector
#                                credential store (github token / confluence api_token /
#                                gdrive client_secret+refresh_token at rest). Absent ⇒ the
#                                credential store stays disabled (current behavior).
#   PROXY_REMOTE_IP_HEADER       reverse-proxy header carrying the real client IP, trusted by
#                                Tomcat's RemoteIpValve (docker/config/server.xml) to populate
#                                request.getRemoteAddr(). Default CF-Connecting-IP (docker1
#                                production sits behind Cloudflare); set to X-Forwarded-For
#                                for Caddy/nginx/ALB/GCLB. ALWAYS injected as
#                                -Dwikantik.proxy.remoteIpHeader=... via CATALINA_OPTS —
#                                unconditionally, not just when set — because Tomcat's
#                                ${...} substitution in server.xml has no default-value
#                                syntax; an undefined property would leave the literal
#                                "${wikantik.proxy.remoteIpHeader}" string as the header
#                                name, silently breaking client-IP resolution. Must match
#                                [A-Za-z0-9-]+ (a valid HTTP header-name token); the script
#                                refuses to boot (exit 1) on a violating value.
#   CATALINA_HOME                tomcat root (default /usr/local/tomcat)

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

CATALINA_HOME="${CATALINA_HOME:-/usr/local/tomcat}"

# --- Generate wikantik-custom.properties ---
cat > "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF
# Auto-generated by entrypoint.sh — do not edit manually

wikantik.applicationName = Wikantik
wikantik.use.external.logconfig = true
wikantik.pageProvider = VersioningFileProvider
wikantik.frontPage = Main

wikantik.baseURL = ${WIKANTIK_BASE_URL:-http://localhost:8080/}

# IndexNow verification key for ping_search_engines (Bing/Yandex). The same
# value must be served publicly at <baseURL>/<key>.txt (a static file shipped
# in the WAR). Blank disables IndexNow notifications.
wikantik.indexnow.apiKey = ${WIKANTIK_INDEXNOW_API_KEY:-}

wikantik.fileSystemProvider.pageDir = ${WIKANTIK_PAGE_DIR:-/var/wikantik/pages}
wikantik.workDir = ${WIKANTIK_WORK_DIR:-/var/wikantik/work}
wikantik.basicAttachmentProvider.storageDir = ${WIKANTIK_ATTACHMENT_DIR:-/var/wikantik/pages}

wikantik.cache.allPagesTTL = 60

# Remember-me: when true, a successful password login issues a Lax, httpOnly,
# scheme-Secure remember-me cookie so a request silently re-authenticates after
# the server session is gone (restart/redeploy/timeout) instead of logging the
# user out. Default off (conservative).
wikantik.cookieAuthentication = ${WIKANTIK_COOKIE_AUTHENTICATION:-false}

# PostgreSQL JDBC user/group database — single shared DataSource named
# jdbc/WikiDatabase. Matches the canonical bare-metal template; the app
# reads the wikantik.datasource property and binds JDBCUserDatabase + JDBCGroupDatabase
# to that one resource.
wikantik.userdatabase = com.wikantik.auth.user.JDBCUserDatabase
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase
wikantik.datasource = jdbc/WikiDatabase

wikantik.userdatabase.table = users
wikantik.userdatabase.uid = uid
wikantik.userdatabase.email = email
wikantik.userdatabase.fullName = full_name
wikantik.userdatabase.loginName = login_name
wikantik.userdatabase.password = password
wikantik.userdatabase.wikiName = wiki_name
wikantik.userdatabase.created = created
wikantik.userdatabase.modified = modified
wikantik.userdatabase.lockExpiry = lock_expiry
wikantik.userdatabase.attributes = attributes
wikantik.userdatabase.roleTable = roles
wikantik.userdatabase.role = role

wikantik.groupdatabase.table = groups
wikantik.groupdatabase.membertable = group_members
wikantik.groupdatabase.name = name
wikantik.groupdatabase.created = created
wikantik.groupdatabase.creator = creator
wikantik.groupdatabase.member = member
wikantik.groupdatabase.modified = modified
wikantik.groupdatabase.modifier = modifier
EOF

# Append SMTP config if host is set
if [ -n "${MAIL_SMTP_HOST:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# SMTP configuration
mail.smtp.host = ${MAIL_SMTP_HOST}
mail.smtp.port = ${MAIL_SMTP_PORT:-587}
mail.smtp.account = ${MAIL_SMTP_ACCOUNT:-}
mail.smtp.password = ${MAIL_SMTP_PASSWORD:-}
mail.smtp.starttls.enable = true
mail.from = ${MAIL_FROM:-wiki@localhost}
EOF
fi

# Optional: dense-retrieval backend override.
#   WIKANTIK_DENSE_BACKEND        — lucene-hnsw | inmemory | pgvector  (default from ini bundle: lucene-hnsw)
#   WIKANTIK_DENSE_EF_SEARCH      — pgvector HNSW recall/latency knob (default 100)
# When unset, the ini-bundle default (lucene-hnsw) wins. Setting pgvector
# requires V032 applied + content_chunk_embeddings.embedding populated
# (run bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh first).
if [ -n "${WIKANTIK_DENSE_BACKEND:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Dense retrieval backend override (entrypoint-injected from env).
wikantik.search.dense.backend            = ${WIKANTIK_DENSE_BACKEND}
wikantik.search.dense.pgvector.ef_search = ${WIKANTIK_DENSE_EF_SEARCH:-100}
EOF
fi

# Optional: Lucene Directory backend override.
#   WIKANTIK_LUCENE_DIRECTORY    — nio | mmap  (default from ini bundle: nio)
# 'mmap' is Lucene's recommended default on 64-bit Linux; flip in .env and
# `bin/remote.sh deploy --skip-build` to restart with the new backend. Rollback
# is the inverse flip + same fast restart.
if [ -n "${WIKANTIK_LUCENE_DIRECTORY:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Lucene Directory backend override (entrypoint-injected from env).
wikantik.search.lucene.directory.kind = ${WIKANTIK_LUCENE_DIRECTORY}
EOF
fi

# Optional: VersioningFileProvider property cache size.
#   WIKANTIK_VERSIONING_CACHE_SIZE — int > 0, default 100 (per ini bundle).
# Properties files are tiny (~hundreds of bytes each), so a generous cap of a
# few thousand entries costs ~MBs of heap and is the right call for a busy
# read workload. Set to 0 to fall back to SingleEntryPropertyCache, -1 to
# disable property caching entirely.
if [ -n "${WIKANTIK_VERSIONING_CACHE_SIZE:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# VersioningFileProvider property cache size (entrypoint-injected from env).
wikantik.versioningFileProvider.cacheSize = ${WIKANTIK_VERSIONING_CACHE_SIZE}
EOF
fi

# Optional: GenAI ceiling control (cost-control flag).
#   WIKANTIK_GENAI_MODE — full | embeddings-only | none (default: ini bundle default = full).
if [ -n "${WIKANTIK_GENAI_MODE:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# GenAI ceiling control (entrypoint-injected from env).
wikantik.genai.mode = ${WIKANTIK_GENAI_MODE}
EOF
fi

# Optional: Knowledge Graph subsystem toggle.
#   WIKANTIK_KNOWLEDGE_ENABLED — "true"/"false", consumed by wikantik.knowledge.enabled
#   (default true when unset; the consuming property ships with the same release).
if [ -n "${WIKANTIK_KNOWLEDGE_ENABLED:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Knowledge Graph subsystem toggle (entrypoint-injected from env).
wikantik.knowledge.enabled = ${WIKANTIK_KNOWLEDGE_ENABLED}
EOF
fi

# Optional: dense-retrieval embedding service base URL override.
#   WIKANTIK_EMBEDDING_BASE_URL — e.g. http://embedding-sidecar:11434 (default: ini bundle
#   default, the shared inference host — unreachable from some networks/cloud deploys).
if [ -n "${WIKANTIK_EMBEDDING_BASE_URL:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Embedding service base URL override (entrypoint-injected from env).
wikantik.search.embedding.base-url = ${WIKANTIK_EMBEDDING_BASE_URL}
EOF
fi

# Optional: KG entity-extractor backend override.
#   WIKANTIK_EXTRACTOR_BACKEND — ollama | claude | disabled (default: ini bundle default =
#   ollama). When "claude", also set ANTHROPIC_API_KEY (read directly from the process
#   environment by EntityExtractorFactory — not rendered here).
if [ -n "${WIKANTIK_EXTRACTOR_BACKEND:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# KG entity-extractor backend override (entrypoint-injected from env).
wikantik.knowledge.extractor.backend = ${WIKANTIK_EXTRACTOR_BACKEND}
EOF
fi

# Optional: connector credential-store encryption key.
#   WIKANTIK_CONNECTORS_CRYPTO_KEY — base64 32-byte AES-256 key. Absent ⇒ the connector
#   credential store (github token / confluence api_token / gdrive client_secret+refresh_token)
#   stays disabled — current behavior.
if [ -n "${WIKANTIK_CONNECTORS_CRYPTO_KEY:-}" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Connector credential-store encryption key (entrypoint-injected from env).
wikantik.connectors.crypto.key = ${WIKANTIK_CONNECTORS_CRYPTO_KEY}
EOF
fi

# Optional: SCIM bearer token (sensitive).
#   WIKANTIK_SCIM_TOKEN — ScimAccessFilter (wikantik-scim) reads this exclusively via
#   System.getProperty("wikantik.scim.token") (see ScimAccessFilter.init(), which checks the
#   system property before falling back to a web.xml filter init-param) — NOT via
#   wikantik-custom.properties/PropertyReader. So it must land as a JVM system property, not
#   a properties-file entry; appended to CATALINA_OPTS, consumed by catalina.sh at the final
#   exec below. Absent ⇒ all SCIM requests are denied (current behavior; ScimAccessFilter
#   logs a warning).
#   Charset guard: catalina.sh word-splits/evals CATALINA_OPTS, so a token containing
#   spaces or shell metacharacters would silently truncate (or worse). Fail fast instead:
#   the token must match [A-Za-z0-9+/=_-]+ (hex/base64/base64url). Generate with:
#   openssl rand -hex 32
if [ -n "${WIKANTIK_SCIM_TOKEN:-}" ]; then
  case "${WIKANTIK_SCIM_TOKEN}" in
    *[!A-Za-z0-9+/=_-]*)
      echo "ERROR: WIKANTIK_SCIM_TOKEN contains characters outside [A-Za-z0-9+/=_-]." >&2
      echo "       catalina.sh word-splits CATALINA_OPTS, so spaces or shell metacharacters" >&2
      echo "       in the token would be silently truncated or misinterpreted. Use a" >&2
      echo "       hex/base64 token only — generate one with: openssl rand -hex 32" >&2
      exit 1
      ;;
  esac
  export CATALINA_OPTS="${CATALINA_OPTS:-} -Dwikantik.scim.token=${WIKANTIK_SCIM_TOKEN}"
fi

# Reverse-proxy client-IP header (RemoteIpValve) — ALWAYS applied, unlike the
# conditional blocks above/below.
#   PROXY_REMOTE_IP_HEADER — header carrying the real client IP (default
#   CF-Connecting-IP for Cloudflare; set to X-Forwarded-For for Caddy/nginx/
#   ALB/GCLB).
# This MUST be unconditional: docker/config/server.xml's RemoteIpValve reads
# remoteIpHeader="${wikantik.proxy.remoteIpHeader}", and Tomcat's ${...}
# substitution has no default-value syntax — if the system property were
# never set, the literal string "${wikantik.proxy.remoteIpHeader}" would
# become the header name, so RemoteIpValve would never match and
# request.getRemoteAddr() would silently keep returning the upstream proxy's
# IP instead of the real client's.
# Deliberately NOT done via a catalina.properties default + conditional -D
# override: decompiling the shipped bootstrap.jar
# (org/apache/catalina/startup/CatalinaProperties.loadProperties()) shows it
# unconditionally calls System.setProperty() for every catalina.properties
# entry during Bootstrap's static init, which runs after the JVM applies -D
# flags — so a catalina.properties default would silently clobber a
# command-line -D override of the same key. Routing exclusively through
# CATALINA_OPTS sidesteps that entirely.
#   Charset guard: catalina.sh word-splits CATALINA_OPTS, and the value
#   becomes an HTTP header name, so it must match [A-Za-z0-9-]+ (letters,
#   digits, hyphen only) — the script refuses to boot (exit 1) otherwise.
PROXY_REMOTE_IP_HEADER_VALUE="${PROXY_REMOTE_IP_HEADER:-CF-Connecting-IP}"
case "${PROXY_REMOTE_IP_HEADER_VALUE}" in
  *[!A-Za-z0-9-]*)
    echo "ERROR: PROXY_REMOTE_IP_HEADER ('${PROXY_REMOTE_IP_HEADER_VALUE}') contains characters outside [A-Za-z0-9-]." >&2
    echo "       This value becomes an HTTP header name (RemoteIpValve remoteIpHeader in" >&2
    echo "       server.xml) and catalina.sh word-splits CATALINA_OPTS, so only letters," >&2
    echo "       digits, and hyphens are valid — e.g. CF-Connecting-IP or X-Forwarded-For." >&2
    exit 1
    ;;
esac
export CATALINA_OPTS="${CATALINA_OPTS:-} -Dwikantik.proxy.remoteIpHeader=${PROXY_REMOTE_IP_HEADER_VALUE}"

# Optional: Single Sign-On (OIDC/SAML via pac4j).
#   WIKANTIK_SSO_ENABLED            "true" to turn SSO on (default off; block skipped when unset/false).
#   WIKANTIK_SSO_TYPE               oidc | saml | both (default oidc).
#   WIKANTIK_SSO_OIDC_DISCOVERY_URI provider OIDC discovery doc, e.g.
#                                   https://accounts.google.com/.well-known/openid-configuration
#   WIKANTIK_SSO_OIDC_CLIENT_ID     OAuth client id from the provider console.
#   WIKANTIK_SSO_OIDC_CLIENT_SECRET OAuth client secret (sensitive — keep in .env.prod, never in git).
#   WIKANTIK_SSO_OIDC_SCOPE         optional; default "openid profile email".
#   WIKANTIK_SSO_IDENTITY_CLAIM     optional; default "sub" (immutable subject). Set to
#                                   preferred_username only to deliberately trust a mutable claim.
#   WIKANTIK_SSO_AUTO_PROVISION     optional; default "true" (create a local profile on first login).
# The OAuth redirect_uri is derived as wikantik.baseURL + /sso/callback, so
# WIKANTIK_BASE_URL must be the public https origin registered with the provider.
if [ "${WIKANTIK_SSO_ENABLED:-false}" = "true" ]; then
  cat >> "${CATALINA_HOME}/lib/wikantik-custom.properties" <<EOF

# Single Sign-On (entrypoint-injected from env).
wikantik.sso.enabled = true
wikantik.sso.type = ${WIKANTIK_SSO_TYPE:-oidc}
wikantik.sso.oidc.discoveryUri = ${WIKANTIK_SSO_OIDC_DISCOVERY_URI:-}
wikantik.sso.oidc.clientId = ${WIKANTIK_SSO_OIDC_CLIENT_ID:-}
wikantik.sso.oidc.clientSecret = ${WIKANTIK_SSO_OIDC_CLIENT_SECRET:-}
wikantik.sso.oidc.scope = ${WIKANTIK_SSO_OIDC_SCOPE:-openid profile email}
wikantik.sso.identityClaim = ${WIKANTIK_SSO_IDENTITY_CLAIM:-sub}
wikantik.sso.autoProvision = ${WIKANTIK_SSO_AUTO_PROVISION:-true}
wikantik.sso.claimMapping.loginName = ${WIKANTIK_SSO_CLAIM_LOGIN_NAME:-preferred_username}
wikantik.sso.claimMapping.fullName = ${WIKANTIK_SSO_CLAIM_FULL_NAME:-name}
wikantik.sso.claimMapping.email = ${WIKANTIK_SSO_CLAIM_EMAIL:-email}
EOF
fi

# --- Generate ROOT.xml (Tomcat context with JNDI DataSources) ---
POSTGRES_HOST="${POSTGRES_HOST:-db}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-wikantik}"
POSTGRES_USER="${POSTGRES_USER:-wikantik}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-CHANGEME}"

JDBC_URL="jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}"

mkdir -p "${CATALINA_HOME}/conf/Catalina/localhost"
cat > "${CATALINA_HOME}/conf/Catalina/localhost/ROOT.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context reloadable="false" cachingAllowed="true">

    <!-- Single shared DataSource. App looks up jdbc/WikiDatabase via the
         wikantik.datasource property; both JDBCUserDatabase and
         JDBCGroupDatabase bind to this one connection pool. Matches the
         shape of the bare-metal Wikantik-context.xml.template so the two
         install paths use identical JNDI conventions. -->
    <Resource name="jdbc/WikiDatabase"
              auth="Container"
              type="javax.sql.DataSource"
              factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
              driverClassName="org.postgresql.Driver"
              url="${JDBC_URL}"
              username="${POSTGRES_USER}"
              password="${POSTGRES_PASSWORD}"
              maxTotal="90"
              maxIdle="30"
              maxWaitMillis="5000"
              validationQuery="SELECT 1"
              testOnBorrow="false"
              testWhileIdle="true"
              timeBetweenEvictionRunsMillis="30000"/>

</Context>
EOF

# --- Generate wikantik-mcp.properties ---
cat > "${CATALINA_HOME}/lib/wikantik-mcp.properties" <<EOF
mcp.server.name = wikantik-mcp
mcp.server.title = Wikantik Knowledge Base
mcp.server.version = 2.0.0
mcp.instructions.file = wikantik-mcp-instructions.txt
mcp.ratelimit.global = ${MCP_RATE_LIMIT_GLOBAL:-100}
mcp.ratelimit.perClient = ${MCP_RATE_LIMIT_PER_CLIENT:-10}
EOF

if [ -n "${MCP_ACCESS_KEYS:-}" ]; then
  echo "mcp.access.keys = ${MCP_ACCESS_KEYS}" >> "${CATALINA_HOME}/lib/wikantik-mcp.properties"
fi

# --- Dev mode: deploy bind-mounted WAR if present ---
if [ -f /tmp/Wikantik.war ]; then
  echo "Dev mode: deploying /tmp/Wikantik.war"
  rm -rf "${CATALINA_HOME}/webapps/ROOT"
  mkdir -p "${CATALINA_HOME}/webapps/ROOT"
  unzip -q -o -d "${CATALINA_HOME}/webapps/ROOT" /tmp/Wikantik.war
fi

# --- Apply DB migrations ---
# Idempotent: schema_migrations table tracks applied versions so re-runs
# only execute new ones. Failure exits non-zero — Tomcat never starts
# against an out-of-date schema. Same script + migrations/ that the
# bare-metal install and CI both use.
if [ -x /opt/wikantik/db/migrate.sh ]; then
  echo "Running database migrations..."
  DB_NAME="${POSTGRES_DB}" \
  DB_APP_USER="${POSTGRES_USER}" \
  PGHOST="${POSTGRES_HOST}" \
  PGPORT="${POSTGRES_PORT}" \
  PGUSER="${POSTGRES_USER}" \
  PGPASSWORD="${POSTGRES_PASSWORD}" \
    /opt/wikantik/db/migrate.sh
fi

# --- Optional dev-user seeding ---
# seed-users.sql ensures the default admin account exists (admin/admin123,
# flagged must-change-on-first-login; insert-if-absent only). Off by default
# (production deploys must NOT inherit known credentials); the bare-metal
# deploy script + the test stack opt in via WIKANTIK_SEED_DEV_USERS=true.
if [ "${WIKANTIK_SEED_DEV_USERS:-false}" = "true" ] && [ -f /opt/wikantik/db/seed-users.sql ]; then
  echo "Ensuring default admin account (admin/admin123, first login forces a change)..."
  PGHOST="${POSTGRES_HOST}" PGPORT="${POSTGRES_PORT}" \
  PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}" \
    psql -d "${POSTGRES_DB}" -f /opt/wikantik/db/seed-users.sql -q || \
    echo "WARN: seed-users.sql failed; check /opt/wikantik/db/seed-users.sql"
fi

# --- First-login guidance ---
# On a fresh database the migrations just seeded admin/admin123 with
# password_must_change=TRUE; tell the operator. Query failure (e.g. exotic
# auth setups) just suppresses the banner — never blocks startup.
ADMIN_MUST_CHANGE="$(PGHOST="${POSTGRES_HOST}" PGPORT="${POSTGRES_PORT}" \
    PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}" \
    psql -d "${POSTGRES_DB}" -tAc \
    "SELECT password_must_change FROM users WHERE login_name='admin'" 2>/dev/null || true)"
if [ "${ADMIN_MUST_CHANGE}" = "t" ]; then
  echo "============================================================"
  echo " Wikantik first start: log in at ${WIKANTIK_BASE_URL:-http://localhost:8080/}"
  echo "   Username: admin"
  echo "   Password: admin123"
  echo " You will be required to choose a new password on first login."
  echo "============================================================"
fi

exec "$@"
