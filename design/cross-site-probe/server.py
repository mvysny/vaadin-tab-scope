#!/usr/bin/env python3
"""Two-site probe harness: http://localhost:8765 is the "app", http://127.0.0.1:8766 the "payment
provider". localhost and 127.0.0.1 have different registrable domains, so a hop between them is a
cross-site top-level navigation."""
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP = "http://localhost:8765"
PAY = "http://127.0.0.1:8766"

# The candidate bootstrap heuristic under test. It runs at the top of <head>, i.e. where a real
# implementation would have to run: before Flow's client reads window.name.
APP_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>app</title>
<script>
(function () {
  const K = 'tabscope:/app';
  const nav = performance.getEntriesByType('navigation')[0];
  const rnd = () => 't' + Math.random().toString(36).slice(2, 8);
  const sig = {
    url: location.href,
    windowName: window.name,
    storedId: sessionStorage.getItem(K + ':id'),
    leftMarker: sessionStorage.getItem(K + ':left'),
    referrer: document.referrer,
    navType: nav && nav.type,
    hasOpener: !!window.opener,
  };
  let verdict, id;
  if (!sig.storedId)                          { verdict = 'new';       id = sig.windowName || rnd(); }
  else if (sig.windowName === sig.storedId)   { verdict = 'same';      id = sig.storedId; }
  else if (sig.leftMarker)                    { verdict = 'returning'; id = sig.storedId; }
  else                                        { verdict = 'copy';      id = rnd(); }
  window.name = id;
  sessionStorage.setItem(K + ':id', id);
  sessionStorage.removeItem(K + ':left');
  sig.verdict = verdict; sig.id = id;
  window.__probe = sig; window.__events = [];
  addEventListener('pagehide', e => { sessionStorage.setItem(K + ':left', String(Date.now())); });
  addEventListener('pageshow', e => {
    window.__events.push('pageshow persisted=' + e.persisted);
    if (e.persisted) sessionStorage.removeItem(K + ':left');
  });
})();
</script></head><body>
<a id="inapp" href="/app?p=2">in-app</a>
<a id="topay" href="%(pay)s/pay">to pay</a>
<a id="bounce" href="%(pay)s/bounce">pure 302 via pay</a>
<a id="blank" href="/app?blank" target="_blank">target=_blank</a>
</body></html>""" % {"pay": PAY}

PAY_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>pay</title>
<script>
window.__pay = { windowName: window.name, url: location.href };
function go(mode) {
  if (mode === 'js') location.href = '%(app)s/app?from=pay-js';
  if (mode === 'form') document.getElementById('f').submit();
}
</script></head><body>
<a id="click" href="%(app)s/app?from=pay-click">return (link)</a>
<a id="bounce" href="/bounce">return (via 302)</a>
<form id="f" method="post" action="%(app)s/app?from=pay-form"><input name="x" value="1"></form>
</body></html>""" % {"app": APP}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send_html(self, body):
        b = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def route(self):
        port = self.server.server_address[1]
        path = self.path.split("?")[0]
        if port == 8765 and path == "/app":
            return self.send_html(APP_PAGE)
        if port == 8766 and path == "/pay":
            return self.send_html(PAY_PAGE)
        if port == 8766 and path == "/bounce":
            self.send_response(302)
            self.send_header("Location", APP + "/app?from=bounce")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.route()

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)
        self.route()


if __name__ == "__main__":
    servers = [ThreadingHTTPServer(("127.0.0.1", p), H) for p in (8765, 8766)]
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("serving", flush=True)
    servers[0].serve_forever()
