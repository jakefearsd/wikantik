#!/usr/bin/env python3
"""cf-probe.py — headless-Chrome probe for the Cloudflare-edge redirect-loop
regression, driven directly through chromedriver's W3C HTTP API.

No Selenium: this talks W3C WebDriver JSON-over-HTTP straight from urllib, the
same protocol Selenium itself sits on top of, without pulling in the package.

It loads a URL headless, watches it for a fixed settle period, and reports
(as JSON) whether the page redirect-looped, whether the SPA actually booted,
and any CSP violations or window errors seen along the way. Exit code is
non-zero when a loop was detected or the SPA never booted, so this can gate a
deploy.

Why polling instead of a hook that fires before the page's own scripts run:
classic WebDriver (unlike CDP's Page.addScriptToEvaluateOnNewDocument) has no
way to inject a script before navigation. We approximate it by navigating
with pageLoadStrategy=none (control returns to us before load/DOMContentLoaded)
and racing to attach listeners in the small window that opens up — good
enough for diagnostics, but not provably complete, which is why the loop
signal itself does NOT depend on those listeners: it comes from repeatedly
reading window.location.href (via the WebDriver "get current URL" command)
over the whole settle window and counting how many distinct URLs show up.
That's robust even if every early error event was missed.

Dependency-free: Python 3 standard library only (urllib, no selenium).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_SETTLE_SECONDS = 12.0
DEFAULT_POLL_INTERVAL = 0.25
CHROME_BINARY_CANDIDATES = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"]

# Installed idempotently, and re-installed on every poll tick: a navigation
# replaces `window`, wiping any listeners attached to the previous document,
# so we keep re-attaching for as long as we're watching.
INSTRUMENT_JS = r"""
if (!window.__cfProbeInit) {
  window.__cfProbeInit = true;
  window.__cfProbeEvents = [];
  window.addEventListener('securitypolicyviolation', function (e) {
    window.__cfProbeEvents.push({
      type: 'csp',
      blockedURI: e.blockedURI,
      violatedDirective: e.violatedDirective,
      disposition: e.disposition
    });
  });
  window.addEventListener('error', function (e) {
    var t = e.target;
    window.__cfProbeEvents.push({
      type: 'error',
      message: e.message || null,
      filename: e.filename || (t && (t.src || t.href)) || null,
      tagName: (t && t.tagName) || null
    });
  }, true);
}
"""

REPORT_JS = r"""
var root = document.getElementById('root');
var errEl = document.getElementById('loading-error');
var visible = false;
if (errEl) {
  var st = window.getComputedStyle(errEl);
  visible = st.visibility !== 'hidden' && st.display !== 'none' && parseFloat(st.opacity || '1') > 0;
}
var nav = performance.getEntriesByType('navigation').map(function (n) {
  return { type: n.type, name: n.name };
});
return {
  readyState: document.readyState,
  rootChildCount: root ? root.children.length : -1,
  loadingErrorVisible: visible,
  navigationEntries: nav,
  events: window.__cfProbeEvents || []
};
"""


class ProbeError(RuntimeError):
    pass


def _http_json(method, url, payload=None, timeout=10):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
    except urllib.error.HTTPError as e:
        body = e.read()
    if not body:
        return {}
    return json.loads(body.decode("utf-8"))


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def find_chrome_binary(explicit: str | None) -> str:
    candidates = [explicit] if explicit else CHROME_BINARY_CANDIDATES
    for c in candidates:
        if c and shutil.which(c):
            return c
    raise ProbeError(
        "no chrome/chromium binary found (tried: %s); pass --chrome-binary" % ", ".join(candidates)
    )


def chrome_version(binary: str) -> str:
    out = subprocess.run([binary, "--version"], capture_output=True, text=True, check=True).stdout
    m = re.search(r"(\d+)\.(\d+)\.(\d+)\.(\d+)", out)
    if not m:
        raise ProbeError("could not parse a version out of %r --version: %r" % (binary, out))
    return m.group(0)


def find_chromedriver(version: str, explicit_path: str | None) -> str:
    if explicit_path:
        return explicit_path
    base = os.path.expanduser("~/.cache/selenium/chromedriver/linux64")
    if not os.path.isdir(base):
        raise ProbeError(
            "no chromedriver cache at %s (chrome is %s); pass --chromedriver-path" % (base, version)
        )
    entries = [d for d in os.listdir(base) if os.path.isfile(os.path.join(base, d, "chromedriver"))]
    if not entries:
        raise ProbeError("no chromedriver binaries found under %s" % base)
    if version in entries:
        return os.path.join(base, version, "chromedriver")
    major = version.split(".")[0]
    same_major = [d for d in entries if d.split(".")[0] == major]
    pool = same_major or entries

    def _key(v: str):
        return tuple(int(p) for p in v.split("."))

    best = sorted(pool, key=_key)[-1]
    if not same_major:
        sys.stderr.write(
            "cf-probe: no chromedriver matches chrome %s exactly or by major version; "
            "falling back to closest available, %s\n" % (version, best)
        )
    return os.path.join(base, best, "chromedriver")


def start_chromedriver(path: str, port: int, verbose: bool) -> subprocess.Popen:
    stdout = None if verbose else subprocess.DEVNULL
    proc = subprocess.Popen([path, "--port=%d" % port], stdout=stdout, stderr=subprocess.STDOUT)
    status_url = "http://127.0.0.1:%d/status" % port
    deadline = time.time() + 10
    while time.time() < deadline:
        if proc.poll() is not None:
            raise ProbeError("chromedriver exited early (code %s)" % proc.returncode)
        try:
            result = _http_json("GET", status_url, timeout=1)
            if result.get("value", {}).get("ready"):
                return proc
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.1)
    proc.terminate()
    raise ProbeError("chromedriver did not report ready within 10s")


def new_session(driver_port: int, chrome_binary: str) -> str:
    binary_path = chrome_binary if os.path.isabs(chrome_binary) else shutil.which(chrome_binary)
    caps = {
        "capabilities": {
            "alwaysMatch": {
                "browserName": "chrome",
                "pageLoadStrategy": "none",
                "goog:chromeOptions": {
                    "binary": binary_path,
                    "args": [
                        "--headless=new",
                        "--no-sandbox",
                        "--disable-gpu",
                        "--disable-dev-shm-usage",
                        "--window-size=1280,900",
                    ],
                },
            }
        }
    }
    result = _http_json("POST", "http://127.0.0.1:%d/session" % driver_port, caps, timeout=20)
    session_id = result.get("value", {}).get("sessionId")
    if not session_id:
        raise ProbeError("failed to create WebDriver session: %s" % json.dumps(result))
    return session_id


def navigate(driver_port: int, session_id: str, url: str) -> None:
    _http_json("POST", "http://127.0.0.1:%d/session/%s/url" % (driver_port, session_id), {"url": url}, timeout=30)


def execute(driver_port: int, session_id: str, script: str):
    payload = {"script": script, "args": []}
    try:
        result = _http_json(
            "POST", "http://127.0.0.1:%d/session/%s/execute/sync" % (driver_port, session_id), payload, timeout=10
        )
    except (urllib.error.URLError, OSError):
        return None  # mid-navigation; caller retries
    if "value" in result and isinstance(result["value"], dict) and "error" in result["value"]:
        return None  # no valid execution context yet; caller retries
    return result.get("value")


def get_url(driver_port: int, session_id: str) -> str | None:
    try:
        result = _http_json("GET", "http://127.0.0.1:%d/session/%s/url" % (driver_port, session_id), timeout=5)
    except (urllib.error.URLError, OSError):
        return None
    return result.get("value")


def quit_session(driver_port: int, session_id: str) -> None:
    try:
        _http_json("DELETE", "http://127.0.0.1:%d/session/%s" % (driver_port, session_id), timeout=5)
    except Exception:
        pass


def run_probe(args: argparse.Namespace) -> dict:
    chrome_binary = find_chrome_binary(args.chrome_binary)
    version = chrome_version(chrome_binary)
    driver_path = find_chromedriver(version, args.chromedriver_path)
    driver_port = _free_port()

    if args.verbose:
        sys.stderr.write("cf-probe: chrome %s, chromedriver %s, driver port %d\n" % (version, driver_path, driver_port))

    proc = start_chromedriver(driver_path, driver_port, args.verbose)
    session_id = None
    try:
        session_id = new_session(driver_port, chrome_binary)
        navigate(driver_port, session_id, args.url)

        # Race to instrument before the page's own scripts (including any
        # injected beacon) finish running. See module docstring.
        install_until = time.time() + min(1.5, args.settle)
        while time.time() < install_until:
            execute(driver_port, session_id, INSTRUMENT_JS)
            time.sleep(0.05)

        url_history: list[str] = []
        last_url = None
        deadline = time.time() + args.settle
        while time.time() < deadline:
            execute(driver_port, session_id, INSTRUMENT_JS)  # re-attach if a reload wiped it
            cur = get_url(driver_port, session_id)
            if cur and cur != last_url:
                url_history.append(cur)
                last_url = cur
            time.sleep(args.poll_interval)

        report = execute(driver_port, session_id, REPORT_JS) or {}
        events = report.get("events", []) or []
        csp_violations = [e for e in events if e.get("type") == "csp"]
        errors = [e for e in events if e.get("type") == "error"]

        url_change_count = max(0, len(url_history) - 1)
        final_url = url_history[-1] if url_history else get_url(driver_port, session_id)

        return {
            "requested_url": args.url,
            "final_url": final_url,
            "url_history": url_history,
            "url_change_count": url_change_count,
            "loop_detected": url_change_count > 1,
            "document_ready_state": report.get("readyState"),
            "root_booted": bool(report.get("rootChildCount", -1) > 0),
            "loading_error_visible": bool(report.get("loadingErrorVisible")),
            "csp_violations": [
                {"blockedURI": e.get("blockedURI"), "violatedDirective": e.get("violatedDirective")}
                for e in csp_violations
            ],
            "errors": [
                {"message": e.get("message"), "filename": e.get("filename"), "tagName": e.get("tagName")}
                for e in errors
            ],
            "navigation_entries": report.get("navigationEntries", []),
        }
    finally:
        if session_id:
            quit_session(driver_port, session_id)
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="cf-probe.py",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        description=(
            "Headless-Chrome probe, driven directly through chromedriver's W3C "
            "HTTP API (no Selenium), that loads a URL and reports whether it "
            "redirect-loops, whether the SPA booted, and any CSP violations or "
            "window errors seen. Exit code is non-zero if a loop was detected or "
            "the SPA never booted, so this can gate a deploy."
        ),
        epilog=(
            "example:\n"
            "  python3 cf-probe.py --url http://localhost:9000/\n"
        ),
    )
    p.add_argument("--url", required=True, help="URL to load — typically cf-edge-sim.py's listen address.")
    p.add_argument("--settle", type=float, default=DEFAULT_SETTLE_SECONDS, metavar="SECONDS",
                    help="How long to watch the page for redirects/errors after navigating.")
    p.add_argument("--poll-interval", type=float, default=DEFAULT_POLL_INTERVAL, metavar="SECONDS",
                    help="How often to sample window.location.href during the settle period.")
    p.add_argument("--chrome-binary", default=None, metavar="PATH",
                    help="Path or name of the chrome/chromium binary (default: auto-detect).")
    p.add_argument("--chromedriver-path", default=None, metavar="PATH",
                    help="Explicit chromedriver binary (default: match against "
                         "~/.cache/selenium/chromedriver/linux64/<version>/chromedriver).")
    p.add_argument("-v", "--verbose", action="store_true", help="Print chromedriver output and progress to stderr.")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        result = run_probe(args)
    except ProbeError as exc:
        print(json.dumps({"error": str(exc)}, indent=2))
        return 2
    except Exception as exc:  # pragma: no cover - defensive
        print(json.dumps({"error": "unexpected: %s" % exc}, indent=2))
        if args.verbose:
            raise
        return 2

    print(json.dumps(result, indent=2))
    return 1 if (result["loop_detected"] or not result["root_booted"]) else 0


if __name__ == "__main__":
    sys.exit(main())
