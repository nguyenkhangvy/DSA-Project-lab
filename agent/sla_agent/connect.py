"""Connect this laptop (spec 2026-10-04-connect-button-design.md, 3). While the student logs in and presses Connect in
the browser, the app listens on 127.0.0.1; the website sends the browser back there with a one-time code, which the
app trades, with the verifier only it knows, for its device key (accounts.connect_laptop, server_client.connect).

One Connection per press of Connect. It knows nothing of the window or the website: it makes the secrets, gives the
Connect page's address, and waits for the browser."""

import base64
import hashlib
import hmac
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

from sla_agent.log import protect

HOST = "127.0.0.1"  # this laptop only: the network can't reach it, and Windows' firewall doesn't ask about it
DONE = "✓ Done. You can close this tab and go back to School-Life-Assistant."
CANCELLED = "Cancelled. You can close this tab."
PAGE = ('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>School-Life-Assistant</title></head>'
        '<body style="font-family: Segoe UI, sans-serif; margin: 3em"><p>{}</p></body></html>')


class Cancelled(Exception):
    """The student pressed Cancel on the website's Connect page."""


class TimedOut(Exception):
    """Nothing came back from the website in time."""


class Closed(Exception):
    """The listening stopped meanwhile: Connect was pressed again, or the page or the window closed."""


def _secret():
    """32 random bytes, URL-safe Base64 without padding: 43 characters, the shape the website checks."""
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")


def challenge_of(verifier):
    """What the Connect page keeps for the trade-in: the verifier's SHA-256, URL-safe Base64 without padding (the
    website's ConnectCodes.challenge)."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


class _Listener(ThreadingHTTPServer):
    allow_reuse_address = False  # with it, Windows would let another program open the same port


class Connection:
    """Listens on 127.0.0.1, at a port Windows picks, from creation until an answer, a timeout or close(). Creating
    one raises OSError when it can't listen."""

    def __init__(self):
        self.state, self.verifier = _secret(), _secret()
        protect(self.state)
        protect(self.verifier)
        self.challenge = challenge_of(self.verifier)
        self._answer = None  # ("code", code) or ("cancelled", None): the first one only
        self._closed = False
        self._lock = threading.Lock()
        self._event = threading.Event()
        self._server = _Listener((HOST, 0), _callback(self))
        self.host, self.port = self._server.server_address[:2]
        threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.1},
                         name="connect-listener", daemon=True).start()

    def url(self, address, name):
        """The website's Connect page for this connection, naming this laptop."""
        query = urlencode({"port": self.port, "state": self.state, "challenge": self.challenge, "name": name})
        return f"{address.rstrip('/')}/school/devices/connect?{query}"

    def wait(self, timeout):
        """The code the browser brought back; raises Cancelled, TimedOut or Closed. Stops listening either way."""
        came = self._event.wait(timeout)
        self.close()
        with self._lock:
            answer = self._answer
        if answer is None:
            raise Closed() if came else TimedOut()
        kind, code = answer
        if kind == "cancelled":
            raise Cancelled()
        return code

    def close(self):
        """Stops listening; a wait() in progress raises Closed. From any thread but the listener's, any number of
        times."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
        self._event.set()
        self._server.shutdown()
        self._server.server_close()

    def _answered(self, kind, code=None):
        with self._lock:
            if self._answer is None and not self._closed:
                self._answer = (kind, code)
        self._event.set()


def _callback(connection):
    """The request handler: GET /callback with this connection's state, and a code or error=cancelled. Anything else,
    a stray request or a page that guessed the port, is "not found" and changes nothing."""

    class Callback(BaseHTTPRequestHandler):
        def do_GET(self):
            parts = urlsplit(self.path)
            query = parse_qs(parts.query)
            code = query.get("code", [""])[0]
            state = query.get("state", [""])[0].encode("utf-8")
            ours = parts.path == "/callback" and hmac.compare_digest(state, connection.state.encode("ascii"))
            if ours and query.get("error") == ["cancelled"]:
                self._page(CANCELLED)
                connection._answered("cancelled")
            elif ours and code:
                protect(code)  # only ours: a stray request must not change what the log hides
                self._page(DONE)
                connection._answered("code", code)
            else:
                self.send_error(404)

        def _page(self, text):
            body = PAGE.format(text).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            """Nothing: the address carries the code, and the built app has no console to write to."""

    return Callback
