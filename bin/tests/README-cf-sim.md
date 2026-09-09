# Cloudflare edge simulator

## The incident this reproduces

Wikantik runs behind a Cloudflare Tunnel. Cloudflare's edge injects a Web
Analytics beacon into every HTML response, just before `</body>`:

```html
<script defer src='https://static.cloudflareinsights.com/beacon.min.js' data-cf-beacon='{"token":"..."}'></script>
```

Wikantik's CSP (`script-src 'self'`) correctly blocks that cross-origin
script, and the browser fires an `error` event on the `<script>` element.
`wikantik-frontend/index.html` carries a boot guard that listens for `error`
on `SCRIPT`/`LINK` tags and treats it as a stale-cached deploy, doing
`window.location.replace(...)` to bust the cache. An earlier version of that
guard reacted to *any* script/link failure, same-origin or not — so the
CSP-blocked beacon retriggered it on every single reload, a rapid,
self-sustaining redirect loop that took hours to diagnose in production. The
guard now ships scoped to same-origin failures only (the
`src.indexOf(window.location.origin) !== 0` check right after the tag-name
check). This harness is what proves that stays true before the next deploy
re-enables Cloudflare's injection — it caught the regression itself in
self-test (see below): the unscoped pattern looped 42 times in 5 seconds
against this harness, the scoped one didn't loop at all.

## The two pieces

- **`cf-edge-sim.py`** — a reverse proxy that sits in front of a local
  Wikantik instance and reproduces the edge behaviors that matter: beacon
  injection, Rocket Loader script rewriting, email-obfuscation script
  injection, ETag stripping on private responses, and `cf-*`
  request/response headers. Every behavior is an independent flag so a
  regression can be bisected.
- **`cf-probe.py`** — a headless-Chrome probe, driven directly through
  chromedriver's W3C HTTP API (no Selenium), that loads a URL, watches it for
  a redirect loop and CSP/window errors, and reports whether the SPA actually
  booted. Non-zero exit on a detected loop or a failed boot, so it gates a
  deploy.

Both are dependency-free — Python 3 standard library only.

## What each `cf-edge-sim.py` flag simulates

| Flag | Simulates |
|---|---|
| `--inject-beacon` / `--no-inject-beacon` (default **on**) | The Cloudflare Web Analytics beacon `<script>`, injected before `</body>`. |
| `--rocket-loader` | Rocket Loader: rewrites `<script src=...>` tags to `type="text/rocketscript"` (skipping `type="module"`, which real Rocket Loader leaves alone) and injects a same-origin `/cdn-cgi/scripts/rocket-loader.min.js` bootstrap. |
| `--email-obfuscation` | Injects a same-origin `/cdn-cgi/scripts/email-decode.min.js` bootstrap. |
| `--strip-etag` | Drops `ETag` on responses whose `Cache-Control` is `private`/`no-cache`/`no-store` — real, observed edge behavior. |
| `--beacon-url URL` / `--beacon-token TOKEN` | Override the injected beacon's `src` and fake token (default is the real production beacon URL). |

Every response also gets `server: cloudflare`, a synthetic `cf-ray`, and
`cf-cache-status: DYNAMIC`. Requests forwarded upstream get `cf-connecting-ip`
and `cf-visitor` added, as the real tunnel does. Requests to
`/cdn-cgi/scripts/*` are answered directly by the sim (edge-served assets
never reach origin in production, so a probe fetching them shouldn't see a
false same-origin 404).

The injected beacon's URL is deliberately **not** guaranteed reachable — the
default is the literal production URL, cross-origin, so it's blocked by
Wikantik's real CSP the same way it is in prod. For a sandboxed/offline
self-test, point `--beacon-url` at something that fails deterministically
without any CSP or network at all, e.g. `http://127.0.0.1:1/beacon.min.js`
(connection refused, no DNS involved) — see the self-test below.

## Running the check

```bash
# 1. Point the sim at your local Wikantik instance (usually :8080)
python3 bin/tests/cf-edge-sim.py --listen 9000 --upstream http://localhost:8080 &

# 2. Probe it — exits non-zero (and says why) if it loops or the SPA never boots
python3 bin/tests/cf-probe.py --url http://localhost:9000/
```

To bisect a suspected interaction, add flags to the first command one at a
time (`--rocket-loader`, `--email-obfuscation`, `--strip-etag`) and re-run the
probe after each.

## Self-test (proves the harness actually detects the defect)

`cf-probe.py` was validated against two hand-written pages serving the same
shape as `wikantik-frontend/index.html` — an inline boot guard plus a script
that will fail to load — one with the guard **unscoped** (reacts to any
script/link error) and one **scoped** (ignores cross-origin failures), both
proxied through `cf-edge-sim.py` with the beacon pointed at
`http://127.0.0.1:1/beacon.min.js` (connection refused, deterministic in any
sandbox). Result:

- Unscoped guard: `loop_detected: true`, `url_change_count: 42`,
  `root_booted: false`, exit code `1`.
- Scoped guard: `loop_detected: false`, `document_ready_state: complete`,
  `root_booted: true`, the beacon's failure still recorded in `errors`
  (proving it *was* observed, just correctly ignored), exit code `0`.
