"""Local preview server for the launcher (the web/ folder).

  python3 -I tools/serve.py [port]      (default port 8765), then open http://localhost:8765/

Serves web/ as static files, plus one route:

  /aerial/<video>.mov   streams https://sylvan.apple.com/Videos/<video>.mov (Apple's Aerial screen-saver
                        videos) with Range support, as video/mp4: browsers refuse Apple's video/quicktime
                        type for <video>, but play the same H.264 bytes when they are labelled mp4.

Listens on 127.0.0.1 only. Static files are sent with no-cache so edits show up on reload.
"""
import http.server
import os
import re
import shutil
import sys
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
AERIAL = re.compile(r"/aerial/([A-Za-z0-9_.-]+\.mov)")


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def end_headers(self):
        if not self.path.startswith("/aerial/"):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def _aerial(self, head):
        m = AERIAL.fullmatch(self.path.split("?")[0])
        req = urllib.request.Request("https://sylvan.apple.com/Videos/" + m.group(1), method="HEAD" if head else "GET")
        if self.headers.get("Range"):
            req.add_header("Range", self.headers["Range"])
        try:
            r = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as e:
            r = e
        except OSError as ex:
            sys.stderr.write(f"aerial proxy error for {m.group(1)}: {ex!r}\n"); sys.stderr.flush()
            self.send_response(502); self.send_header("Content-Length", "0"); self.end_headers(); return
        self.send_response(r.status)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "max-age=86400")
        for k in ("Content-Length", "Content-Range"):
            if r.headers.get(k):
                self.send_header(k, r.headers[k])
        self.end_headers()
        if not head:
            try:
                shutil.copyfileobj(r, self.wfile, 256 * 1024)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_GET(self):
        if AERIAL.fullmatch(self.path.split("?")[0]):
            return self._aerial(False)
        return super().do_GET()

    def do_HEAD(self):
        if AERIAL.fullmatch(self.path.split("?")[0]):
            return self._aerial(True)
        return super().do_HEAD()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print(f"Serving {ROOT} on http://localhost:{PORT}", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
