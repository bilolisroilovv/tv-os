#!/usr/bin/env python3
"""README screenshots of the launcher in its demo state (demo apps, gradient wallpapers, no local photos).

  python3 -I tools/screenshots.py            -> docs/screenshots/*.png (1920x1080)

Runs a Chromium-based browser (Chrome, Chromium, Brave or Edge; or BROWSER=/path) headless and drives it over the
DevTools protocol: opens index.html?shot=<name> (see web/dev/shot.js), waits until the page says it is ready, then
captures the screen. Python standard library only.
"""
import base64, json, os, shutil, socket, struct, subprocess, sys, tempfile, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = [  # file, query
    ("home", "shot=home&wp=g-dusk"), ("grid", "shot=grid&wp=g-dusk"), ("control-center", "shot=cc&wp=g-ocean"),
    ("menu", "shot=menu&wp=g-aurora&dark=1"), ("settings", "shot=settings&wp=g-dusk"), ("settings-saver", "shot=saver&wp=g-ocean&dark=1"),
    ("settings-performance", "shot=perf&wp=g-dawn"), ("settings-apps", "shot=apps&wp=g-aurora&dark=1"), ("keyboard", "shot=keyboard&wp=g-dusk"),
    ("switcher", "shot=switcher&wp=g-ocean"), ("alert", "shot=alert&wp=g-dusk&dark=1"), ("wallpapers", "shot=picker&wp=g-dawn"),
]


def browser():
    cands = [os.environ.get("BROWSER", ""), "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
             "/Applications/Chromium.app/Contents/MacOS/Chromium", "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
             "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"] + [shutil.which(n) or "" for n in ("google-chrome", "chromium", "chromium-browser")]
    for c in cands:
        if c and os.path.exists(c):
            return c
    sys.exit("Нужен Chrome, Chromium, Brave или Edge (или BROWSER=путь).")


class Tab:
    """A minimal DevTools websocket client."""
    def __init__(self, ws_url):
        host, path = ws_url.split("//", 1)[1].split("/", 1)
        h, p = host.split(":")
        self.s = socket.create_connection((h, int(p)))
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.send(f"GET /{path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            self.buf += self.s.recv(4096)
        self.buf = self.buf.split(b"\r\n\r\n", 1)[1]
        self.n = 0

    def _need(self, n):
        while len(self.buf) < n:
            self.buf += self.s.recv(1 << 20)

    def _recv(self):
        self._need(2)
        n, off = self.buf[1] & 127, 2
        if n == 126:
            self._need(4); n = struct.unpack(">H", self.buf[2:4])[0]; off = 4
        elif n == 127:
            self._need(10); n = struct.unpack(">Q", self.buf[2:10])[0]; off = 10
        self._need(off + n)
        d, self.buf = self.buf[off:off + n], self.buf[off + n:]
        return json.loads(d)

    def call(self, method, **params):
        self.n += 1
        d = json.dumps({"id": self.n, "method": method, "params": params}).encode()
        m = os.urandom(4)
        n = len(d)
        head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0xFE]) + struct.pack(">H", n) if n < 65536 else bytes([0xFF]) + struct.pack(">Q", n))
        self.s.send(head + m + bytes(b ^ m[i % 4] for i, b in enumerate(d)))
        while True:
            r = self._recv()
            if r.get("id") == self.n:
                return r.get("result", {})


def main():
    port, dport = 8799, 9555
    out = os.path.join(ROOT, "docs", "screenshots")
    os.makedirs(out, exist_ok=True)
    srv = subprocess.Popen([sys.executable, "-I", os.path.join(ROOT, "tools", "serve.py"), str(port)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    prof = tempfile.mkdtemp()
    br = subprocess.Popen([browser(), "--headless=new", f"--remote-debugging-port={dport}", f"--user-data-dir={prof}", "--window-size=1920,1080",
                           "--force-device-scale-factor=1", "--hide-scrollbars", "--mute-audio", "--no-first-run", "about:blank"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            try:
                pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{dport}/json"))
                break
            except OSError:
                time.sleep(0.2)
        tab = Tab([p for p in pages if p.get("type") == "page"][0]["webSocketDebuggerUrl"])
        tab.call("Emulation.setDeviceMetricsOverride", width=1920, height=1080, deviceScaleFactor=1, mobile=False)
        for name, query in SHOTS:
            tab.call("Page.navigate", url=f"http://localhost:{port}/index.html?{query}")
            for _ in range(120):  # up to 30 s
                time.sleep(0.25)
                if tab.call("Runtime.evaluate", expression="document.title", returnByValue=True).get("result", {}).get("value") == "ready":
                    break
            time.sleep(0.8)
            png = tab.call("Page.captureScreenshot", format="png").get("data", "")
            open(os.path.join(out, name + ".png"), "wb").write(base64.b64decode(png))
            print("  docs/screenshots/" + name + ".png")
    finally:
        br.terminate(); srv.terminate()
        shutil.rmtree(prof, ignore_errors=True)


if __name__ == "__main__":
    main()
