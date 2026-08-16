# Wikantik External Source Connectors

External-source connector runtime for derived pages: seven connector
implementations (filesystem, web crawler, sitemap, RSS/Atom feed, Google
Drive OAuth2, GitHub, Confluence) that sync content in via `SyncOrchestrator`
with hash-dedup, tombstones, and cursor-resume. Six of the seven are
creatable from the admin UI; `filesystem` is properties-defined only,
deliberately excluded from admin creation because it reads arbitrary
server-local paths.

Connector configuration, credentials, and per-run history are managed
through admin-facing services in `wikantik-main`; this module holds the
connector types themselves plus the shared HTTP and state-tracking
infrastructure they build on.
