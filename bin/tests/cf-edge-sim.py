#!/usr/bin/env python3
"""cf-edge-sim.py — simulate Cloudflare's edge in front of a local origin.

Cloudflare's edge (Web Analytics beacon injection, Rocket Loader, email
obfuscation, and its own response/request headers) sits between every
production request and Wikantik. None of that exists when you test against
`localhost:8080` directly, so bugs that only manifest through the edge (like
the redirect-loop incident this tool was built to catch — see
bin/tests/README-cf-sim.md) are invisible until you actually deploy behind
the real tunnel. This proxy reproduces the parts of that edge that matter for
front-end correctness, with each behavior individually toggleable so a
regression can be bisected flag-by-flag.

Dependency-free: Python 3 standard library only.

Example:
    python3 bin/tests/cf-edge-sim.py --listen 9000 --upstream http://localhost:8080
    curl -sD- http://localhost:9000/ | head -40

    # Bisect a suspected Rocket Loader interaction:
    python3 bin/tests/cf-edge-sim.py --listen 9000 --upstream http://localhost:8080 \
        --rocket-loader --no-inject-beacon
"""
from __future__ import annotations

import argparse
import gzip
import html as html_module
import http.client
import http.server
import random
import re
import socketserver
import sys
import zlib
from types import SimpleNamespace
from urllib.parse import urlsplit

# ---------------------------------------------------------------------------
# Constants matching real Cloudflare behavior
# ---------------------------------------------------------------------------

# The literal production beacon URL from the incident this harness reproduces.
# Deliberately cross-origin: this is exactly what a same-origin-only CSP
# (script-src 'self') blocks, and what makes the injected <script> fire an
# `error` event that a naive boot guard mistakes for a stale deploy.
DEFAULT_BEACON_URL = "https://static.cloudflareinsights.com/beacon.min.js"
DEFAULT_BEACON_TOKEN = "0123456789abcdef0123456789abcdef"

ROCKET_LOADER_TAG = (
    "<script src='/cdn-cgi/scripts/rocket-loader.min.js' "
    "data-cf-settings='cf-edge-sim,auto' defer></script>"
)
EMAIL_DECODE_TAG = (
    "<script data-cfasync='false' src='/cdn-cgi/scripts/email-decode.min.js'></script>"
)

CDN_CGI_ASSETS = {"rocket-loader.min.js", "email-decode.min.js"}

# Headers that must never be copied verbatim from one hop to the next.
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "trailers", "transfer-encoding", "upgrade",
}

CF_COLOS = ["SEA", "LAX", "ORD", "IAD", "ATL", "DFW", "EWR", "SJC", "MIA", "DEN"]

_BODY_CLOSE_RE = re.compile(r"</body\s*>", re.IGNORECASE)
_SCRIPT_TAG_RE = re.compile(r"<script\b[^>]*>", re.IGNORECASE)
_TYPE_ATTR_RE = re.compile(r'type\s*=\s*(["\'])[^"\']*\1', re.IGNORECASE)
_CHARSET_RE = re.compile(r"charset=([^\s;]+)", re.IGNORECASE)


def make_cf_ray() -> str:
    """A plausible-looking CF-RAY: 16 hex chars, a colo code, like the real ones."""
    return "%016x-%s" % (random.getrandbits(64), random.choice(CF_COLOS))


def build_beacon_tag(beacon_url: str, token: str) -> str:
    # Matches the real markup verbatim (single-quoted attrs) so this is a
    # faithful reproduction, not an approximation.
    return "<script defer src='%s' data-cf-beacon='{\"token\":\"%s\"}'></script>" % (
        beacon_url, token,
    )


def rocket_loader_rewrite(text: str) -> str:
    """Rewrite <script src=...> tags the way Cloudflare's Rocket Loader does:
    swap type to text/rocketscript so the browser won't execute them directly
    (rocket-loader.min.js fetches + evals them itself later). Real Rocket
    Loader skips `type="module"` scripts — ES modules can't be deferred this
    way — so this does too.
    """

    def repl(m: "re.Match[str]") -> str:
        tag = m.group(0)
        if re.search(r"\bsrc\s*=", tag, re.IGNORECASE) is None:
            return tag  # inline script, nothing to rewrite
        if re.search(r'type\s*=\s*["\']module["\']', tag, re.IGNORECASE):
            return tag  # Rocket Loader deliberately leaves modules alone
        if "data-cf-original-src" in tag.lower():
            return tag  # already rewritten (defensive, shouldn't happen)
        if _TYPE_ATTR_RE.search(tag):
            return _TYPE_ATTR_RE.sub('type="text/rocketscript"', tag, count=1)
        return tag[: len("<script")] + ' type="text/rocketscript"' + tag[len("<script"):]

    return _SCRIPT_TAG_RE.sub(repl, text)


def inject_all(text: str, cfg: SimpleNamespace) -> str:
    if cfg.rocket_loader:
        text = rocket_loader_rewrite(text)

    snippets = []
    if cfg.rocket_loader:
        snippets.append(ROCKET_LOADER_TAG)
    if cfg.email_obfuscation:
        snippets.append(EMAIL_DECODE_TAG)
    if cfg.inject_beacon:
        snippets.append(build_beacon_tag(cfg.beacon_url, cfg.beacon_token))
    if not snippets:
        return text

    injected = "\n" + "\n".join(snippets) + "\n"
    m = _BODY_CLOSE_RE.search(text)
    if m:
        return text[: m.start()] + injected + text[m.start():]
    return text + injected


def extract_charset(content_type: str) -> str | None:
    m = _CHARSET_RE.search(content_type or "")
    return m.group(1).strip("\"'") if m else None


def is_strippable_cache_control(cache_control: str) -> bool:
    cc = (cache_control or "").lower()
    return "private" in cc or "no-cache" in cc or "no-store" in cc


# ---------------------------------------------------------------------------
# The proxy handler
# ---------------------------------------------------------------------------

class EdgeProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "cf-edge-sim/1.0"

    # Set on the class before serve_forever() — see main().
    config: SimpleNamespace

    def log_message(self, fmt: str, *args) -> None:  # noqa: D401 - stdlib override
        if self.config.verbose:
            sys.stderr.write("[cf-edge-sim] %s - %s\n" % (self.address_string(), fmt % args))

    # -- request body -------------------------------------------------------

    def _read_body(self) -> bytes:
        length = self.headers.get("Content-Length")
        if length is not None:
            return self.rfile.read(int(length))
        if (self.headers.get("Transfer-Encoding", "") or "").lower() == "chunked":
            return self._read_chunked()
        return b""

    def _read_chunked(self) -> bytes:
        chunks = []
        while True:
            size_line = self.rfile.readline().strip()
            if not size_line:
                break
            size = int(size_line.split(b";")[0], 16)
            if size == 0:
                self.rfile.readline()
                break
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)  # trailing CRLF
        return b"".join(chunks)

    # -- HTTP methods ---------------------------------------------------------

    def do_GET(self) -> None: self._proxy("GET")
    def do_POST(self) -> None: self._proxy("POST")
    def do_PUT(self) -> None: self._proxy("PUT")
    def do_DELETE(self) -> None: self._proxy("DELETE")
    def do_PATCH(self) -> None: self._proxy("PATCH")
    def do_HEAD(self) -> None: self._proxy("HEAD")
    def do_OPTIONS(self) -> None: self._proxy("OPTIONS")

    # -- edge-served synthetic assets ---------------------------------------
    #
    # Real Cloudflare serves rocket-loader.min.js / email-decode.min.js from
    # the edge itself, never from origin. If a probe fetches these and gets a
    # same-origin 404, that's a false failure signal unrelated to the app —
    # so the sim answers them directly instead of proxying upstream.

    def _serve_cdn_cgi_asset(self, method: str) -> None:
        name = self.path.split("?", 1)[0].rsplit("/", 1)[-1]
        if name not in CDN_CGI_ASSETS:
            body = b"cf-edge-sim: unknown /cdn-cgi/scripts/ asset\n"
            self._send_simple(404, "Not Found", body, "text/plain; charset=utf-8")
            return
        body = ("/* cf-edge-sim stub for %s: a same-origin edge asset, always resolves */\n" % name).encode()
        headers = [("Cache-Control", "no-store")]
        self._send_simple(200, "OK", body if method != "HEAD" else b"", "application/javascript; charset=utf-8", extra_headers=headers, content_length=len(body))

    def _send_simple(self, status, reason, body, content_type, extra_headers=None, content_length=None):
        self.send_response_only(status, reason)
        self.send_header("Date", self.date_time_string())
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(content_length if content_length is not None else len(body)))
        for k, v in (extra_headers or []):
            self.send_header(k, v)
        self.send_header("Server", "cloudflare")
        self.send_header("CF-RAY", make_cf_ray())
        self.send_header("CF-Cache-Status", "DYNAMIC")
        self.end_headers()
        if body:
            self.wfile.write(body)

    # -- the actual proxy -----------------------------------------------------

    def _proxy(self, method: str) -> None:
        cfg = self.config

        if self.path.startswith("/cdn-cgi/scripts/"):
            self._read_body()  # drain any body so keep-alive framing stays sane
            self._serve_cdn_cgi_asset(method)
            return

        body = self._read_body()

        upstream_headers = {}
        for k, v in self.headers.items():
            if k.lower() in HOP_BY_HOP or k.lower() == "host":
                continue
            upstream_headers[k] = v

        # What a real Cloudflare Tunnel adds when it forwards to origin.
        upstream_headers["Host"] = cfg.upstream_netloc
        upstream_headers["Accept-Encoding"] = "identity"
        upstream_headers["CF-Connecting-IP"] = self.client_address[0]
        upstream_headers["CF-Visitor"] = '{"scheme":"https"}'
        upstream_headers["CF-RAY"] = make_cf_ray()
        upstream_headers["X-Forwarded-Proto"] = "https"
        upstream_headers["X-Forwarded-For"] = self.client_address[0]

        conn_cls = http.client.HTTPSConnection if cfg.upstream_scheme == "https" else http.client.HTTPConnection
        conn = conn_cls(cfg.upstream_host, cfg.upstream_port, timeout=cfg.timeout)
        try:
            conn.request(method, self.path, body=body or None, headers=upstream_headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            resp_headers = resp.getheaders()  # list of tuples: preserves duplicates (e.g. Set-Cookie)
            status, reason = resp.status, resp.reason
        except (OSError, http.client.HTTPException) as exc:
            self._send_bad_gateway(exc)
            return
        finally:
            conn.close()

        headers_lower = {k.lower(): v for k, v in resp_headers}

        # We asked upstream for identity encoding, but be defensive in case it
        # ignored that — decompress rather than forward a mismatched body.
        encoding = headers_lower.get("content-encoding", "").lower()
        if encoding in ("gzip", "x-gzip"):
            try:
                resp_body = gzip.decompress(resp_body)
                encoding = ""
            except OSError as exc:
                self.log_message("failed to gunzip upstream body: %s", exc)
        elif encoding == "deflate":
            try:
                resp_body = zlib.decompress(resp_body)
            except zlib.error:
                resp_body = zlib.decompress(resp_body, -zlib.MAX_WBITS)
            encoding = ""

        content_type = headers_lower.get("content-type", "")
        is_html = content_type.split(";")[0].strip().lower() == "text/html"

        if is_html and method != "HEAD":
            charset = extract_charset(content_type) or "utf-8"
            try:
                text = resp_body.decode(charset, errors="replace")
            except LookupError:
                charset = "utf-8"
                text = resp_body.decode(charset, errors="replace")
            text = inject_all(text, cfg)
            resp_body = text.encode(charset, errors="replace")

        strip_etag = cfg.strip_etag and is_strippable_cache_control(headers_lower.get("cache-control", ""))

        out_headers = []
        for k, v in resp_headers:
            lk = k.lower()
            if lk in HOP_BY_HOP or lk in ("content-length", "server", "date"):
                continue
            if lk == "content-encoding" and encoding == "":
                continue  # stale now that we've decompressed / never had one
            if lk == "etag" and strip_etag:
                continue
            out_headers.append((k, v))

        self.send_response_only(status, reason)
        self.send_header("Date", self.date_time_string())
        for k, v in out_headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(resp_body)))
        self.send_header("Server", "cloudflare")
        self.send_header("CF-RAY", make_cf_ray())
        self.send_header("CF-Cache-Status", "DYNAMIC")
        self.end_headers()
        if method != "HEAD":
            self.wfile.write(resp_body)

    def _send_bad_gateway(self, exc: Exception) -> None:
        body = (
            "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head><body>"
            "<h1>502 Bad Gateway</h1><p>cf-edge-sim: upstream connection failed: %s</p>"
            "</body></html>" % html_module.escape(str(exc))
        ).encode("utf-8")
        self.send_response_only(502, "Bad Gateway")
        self.send_header("Date", self.date_time_string())
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Server", "cloudflare")
        self.send_header("CF-RAY", make_cf_ray())
        self.end_headers()
        self.wfile.write(body)


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="cf-edge-sim.py",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        description=(
            "Reverse proxy that reproduces the parts of Cloudflare's edge that "
            "matter for front-end correctness — Web Analytics beacon injection, "
            "Rocket Loader script rewriting, email-address obfuscation, ETag "
            "stripping, and cf-* headers — in front of a locally-running origin. "
            "Pair with cf-probe.py to prove the SPA survives it before deploying."
        ),
        epilog=(
            "example:\n"
            "  python3 cf-edge-sim.py --listen 9000 --upstream http://localhost:8080\n"
            "  curl -sD- http://localhost:9000/ | head -40\n"
        ),
    )
    p.add_argument("--listen", type=int, required=True, metavar="PORT",
                    help="Port to listen on (the simulated edge).")
    p.add_argument("--upstream", required=True, metavar="URL",
                    help="Origin to proxy to, e.g. http://localhost:8080")
    p.add_argument("--inject-beacon", dest="inject_beacon", default=True,
                    action=argparse.BooleanOptionalAction,
                    help="Inject the Cloudflare Web Analytics beacon <script> before </body>.")
    p.add_argument("--rocket-loader", action="store_true",
                    help='Rewrite <script src> tags to type="text/rocketscript" '
                         "(skipping type=module) and inject the Rocket Loader bootstrap.")
    p.add_argument("--email-obfuscation", action="store_true",
                    help="Inject the email-decode.min.js bootstrap script.")
    p.add_argument("--strip-etag", action="store_true",
                    help="Drop ETag on responses whose Cache-Control is "
                         "private/no-cache/no-store — observed real edge behavior.")
    p.add_argument("--beacon-url", default=DEFAULT_BEACON_URL, metavar="URL",
                    help="URL used as the injected beacon's src.")
    p.add_argument("--beacon-token", default=DEFAULT_BEACON_TOKEN, metavar="TOKEN",
                    help="Fake token embedded in the beacon's data-cf-beacon attribute.")
    p.add_argument("--timeout", type=float, default=10.0, metavar="SECONDS",
                    help="Upstream request timeout.")
    p.add_argument("-v", "--verbose", action="store_true",
                    help="Log every proxied request to stderr.")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)

    parts = urlsplit(args.upstream)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        print("cf-edge-sim: --upstream must look like http://host:port", file=sys.stderr)
        return 2

    config = SimpleNamespace(
        upstream_scheme=parts.scheme,
        upstream_host=parts.hostname,
        upstream_port=parts.port or (443 if parts.scheme == "https" else 80),
        upstream_netloc=parts.netloc,
        inject_beacon=args.inject_beacon,
        rocket_loader=args.rocket_loader,
        email_obfuscation=args.email_obfuscation,
        strip_etag=args.strip_etag,
        beacon_url=args.beacon_url,
        beacon_token=args.beacon_token,
        timeout=args.timeout,
        verbose=args.verbose,
    )

    EdgeProxyHandler.config = config
    server = ThreadingHTTPServer(("0.0.0.0", args.listen), EdgeProxyHandler)
    print(
        "cf-edge-sim: listening on :%d -> %s (beacon=%s rocket-loader=%s "
        "email-obfuscation=%s strip-etag=%s)"
        % (args.listen, args.upstream, config.inject_beacon, config.rocket_loader,
           config.email_obfuscation, config.strip_etag),
        file=sys.stderr,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
