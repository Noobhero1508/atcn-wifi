import importlib.util
import json
import pathlib
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODULE_PATH = pathlib.Path(__file__).with_name("atcn_wifi_core.py")
spec = importlib.util.spec_from_file_location("wifi_tool", MODULE_PATH)
wifi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wifi)


class TestPortal(BaseHTTPRequestHandler):
    authenticated = False
    missing_until_logout = False
    logout_done = False
    logout_count = 0
    verify_count = 0
    analytics_count = 0
    last_awing_query = {}
    router_post_cookie = ""

    def log_message(self, *_args):
        pass

    @classmethod
    def reset(cls, missing_until_logout=False):
        cls.authenticated = False
        cls.missing_until_logout = missing_until_logout
        cls.logout_done = False
        cls.logout_count = 0
        cls.verify_count = 0
        cls.analytics_count = 0
        cls.last_awing_query = {}
        cls.router_post_cookie = ""

    def _send(self, status, body=b"", content_type="text/html", location=None):
        self.send_response(status)
        if location:
            self.send_header("Location", location)
        if body:
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/router/login":
            if self.missing_until_logout and not self.logout_done:
                self._send(200, b"<html>temporary blank router page</html>")
                return
            body = r"""
                <script>
                const wifiInfo = {
                    "logged-in": "no",
                    mac: "4C:23:38:C5:88:3B",
                    ip: "186.186.1.10",
                    chap_id: '\107',
                    chap_challenge: '\245\157\272\021'
                };
                </script>
            """.encode("latin-1")
            self.send_response(200)
            self.send_header("Set-Cookie", "router_session=fresh; Path=/")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/router/status":
            self._send(200, b"<html>MikroTik status</html>")
            return
        if path == "/router/logout":
            TestPortal.logout_done = True
            TestPortal.logout_count += 1
            self._send(302, location="/router/login")
            return
        if path in {"/awing/login", "/login"}:
            TestPortal.last_awing_query = urllib.parse.parse_qs(
                urllib.parse.urlsplit(self.path).query
            )
            body = b"<html><script src='/captive/awing-captive.min.js'></script></html>"
            self._send(200, body)
            return
        if path == "/probe":
            self._send(302, location="/login?fresh=1")
            return
        self._send(404)

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/Home/VerifyUrl":
            TestPortal.verify_count += 1
            host = self.headers["Host"]
            form = (
                f"<form id='frmLogin' method='post' action='http://{host}/router/login'>"
                "<input name='username' value='dynamic-user'>"
                "<input name='password' value='dynamic-chap-hash'>"
                "<input name='dst' value='http://example.test/Success'>"
                "<input name='popup' value='false'>"
                "</form>"
            )
            # First response is the real-world AWING blank-load failure; F5 succeeds.
            auth_form = "" if TestPortal.verify_count == 1 else form
            body = json.dumps(
                {
                    "token": "verify-token",
                    "captiveContext": {"contentAuthenForm": auth_form},
                }
            ).encode()
            self._send(200, body, "application/json")
            return
        if path == "/Analytic/Send":
            TestPortal.analytics_count += 1
            body = json.dumps(
                {"token": "updated-token", "captiveContext": {}}
            ).encode()
            self._send(200, body, "application/json")
            return
        if path == "/router/login":
            length = int(self.headers.get("Content-Length", "0"))
            params = urllib.parse.parse_qs(self.rfile.read(length).decode())
            TestPortal.router_post_cookie = self.headers.get("Cookie", "")
            if (
                params.get("username") == ["dynamic-user"]
                and params.get("password") == ["dynamic-chap-hash"]
            ):
                TestPortal.authenticated = True
                self._send(302, location="/success")
            else:
                self._send(403, b"bad credentials")
            return
        self._send(404)


def configure(origin):
    wifi.GATEWAY_LOGIN = origin + "/router/login"
    wifi.GATEWAY_LOGOUT = origin + "/router/logout?"
    wifi.GATEWAY_STATUS = origin + "/router/status"
    wifi.AWING_BASE_URL = origin
    wifi.AWING_LOGIN_URL = origin + "/awing/login"
    wifi.AWING_SUCCESS_URL = origin + "/Success"
    wifi.AWING_VERIFY_URL = origin + "/Home/VerifyUrl"
    wifi.AWING_ANALYTIC_URL = origin + "/Analytic/Send"
    wifi.PORTAL_PROBE_URLS = (origin + "/probe",)
    wifi.LOGOUT_WAIT_SECONDS = 0
    wifi.check_internet = lambda timeout=wifi.CHECK_TIMEOUT: TestPortal.authenticated


def test_parser():
    html = r"""
        const wifiInfo = {
            'logged-in': 'no', mac: 'AA:BB:CC:DD:EE:FF', ip: '186.186.1.20',
            chap_id: '\107', chap_challenge: '\xA5\157\u00BA\021'
        };
    """
    info = wifi.extract_wifi_info(html)
    assert info["logged_in"] == "no"
    assert info["chap_id"] == "G"
    assert [ord(char) for char in info["chap_challenge"]] == [0xA5, 0o157, 0xBA, 0o21]
    assert wifi._to_url_octal(info["chap_challenge"]) == "\\245\\157\\272\\021"


server = ThreadingHTTPServer(("127.0.0.1", 0), TestPortal)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    origin = f"http://127.0.0.1:{server.server_port}"
    configure(origin)
    test_parser()

    TestPortal.reset()
    success, reason = wifi.router_api_login({"logout_used": False}, use_analytics=False)
    assert success, reason
    assert TestPortal.verify_count >= 2
    assert TestPortal.logout_count == 0
    assert "router_session=fresh" in TestPortal.router_post_cookie
    assert TestPortal.last_awing_query["serial"] == [wifi.DEFAULT_SERIAL]
    assert TestPortal.last_awing_query["chap_id"] == ["\\107"]

    TestPortal.reset(missing_until_logout=True)
    success, reason = wifi.router_api_login({"logout_used": False}, use_analytics=False)
    assert success, reason
    assert TestPortal.logout_count == 1

    TestPortal.reset()
    success, reason = wifi.router_api_login({"logout_used": False}, use_analytics=True)
    assert success, reason
    assert TestPortal.analytics_count == 2

    TestPortal.reset()
    success, reason = wifi.generic_http_login()
    assert success, reason

    print("parser=PASS")
    print("direct_api_with_auto_f5=PASS")
    print("guarded_force_logout=PASS")
    print("analytics_compatibility_fallback=PASS")
    print("browser_free_http_fallback=PASS")
finally:
    server.shutdown()
    server.server_close()
