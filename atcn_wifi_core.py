import argparse
import copy
import http.cookiejar
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path


# MikroTik/AWING configuration currently verified at KTX khu A, ĐHQG-HCM.
GATEWAY_LOGIN = "http://186.186.0.1/login"
GATEWAY_LOGOUT = "http://186.186.0.1/logout?"
GATEWAY_STATUS = "http://186.186.0.1/status"

AWING_BASE_URL = "http://v1.awingconnect.vn"
AWING_LOGIN_URL = f"{AWING_BASE_URL}/login"
AWING_SUCCESS_URL = f"{AWING_BASE_URL}/Success"
AWING_VERIFY_URL = f"{AWING_BASE_URL}/Home/VerifyUrl"
AWING_ANALYTIC_URL = f"{AWING_BASE_URL}/Analytic/Send"

DEFAULT_SERIAL = "CC:2D:E0:19:00:6C"
CHECK_URLS = (
    "http://connectivitycheck.gstatic.com/generate_204",
    "http://www.msftconnecttest.com/connecttest.txt",
)
PORTAL_PROBE_URLS = (
    "http://awifi.vn",
    *CHECK_URLS,
    "http://neverssl.com/",
)
USER_AGENT = (
    "Mozilla/5.0 (AppleWebKit-compatible; ATCN-Wifi/1.1) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36"
)

CHECK_TIMEOUT = 2.5
REQUEST_TIMEOUT = 7
MONITOR_INTERVAL = 5
AWING_REFRESH_ATTEMPTS = 3
ROUTER_READ_ATTEMPTS = 2
LOGOUT_WAIT_SECONDS = 3
ENABLE_ANALYTICS_FALLBACK = True


def _application_data_dir():
    """Return a user-writable data directory on Windows, macOS and Linux."""
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        return base / "ATCN Wifi"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Logs" / "ATCN Wifi"
    base = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return base / "atcn-wifi"


ERROR_LOG = _application_data_dir() / "wifi_errors.log"

_event_callback = None


if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Expose a redirect immediately instead of downloading the destination."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoginFormParser(HTMLParser):
    """Extract the hidden MikroTik form returned by AWING."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self._form = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag.lower() == "form":
            self._form = {
                "id": attrs.get("id", ""),
                "name": attrs.get("name", ""),
                "action": attrs.get("action", ""),
                "method": attrs.get("method", "get").lower(),
                "fields": {},
            }
        elif tag.lower() == "input" and self._form is not None:
            name = attrs.get("name")
            if name:
                self._form["fields"][name] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag.lower() == "form" and self._form is not None:
            self.forms.append(self._form)
            self._form = None


def set_event_callback(callback):
    """Register a GUI-safe event sink accepting (message, level)."""
    global _event_callback
    _event_callback = callback


def _emit(message, level="info"):
    print(message)
    if _event_callback is not None:
        try:
            _event_callback(message, level)
        except Exception:
            pass


def _request(url, data=None, method=None):
    return urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache, no-store, max-age=0",
            "Pragma": "no-cache",
            "Connection": "close",
        },
    )


def _new_session(cookie_jar=None, no_redirect=False):
    cookie_jar = cookie_jar or http.cookiejar.CookieJar()
    handlers = [urllib.request.HTTPCookieProcessor(cookie_jar)]
    if no_redirect:
        handlers.append(NoRedirect())
    return urllib.request.build_opener(*handlers), cookie_jar


def _read_html(response, limit=2_000_000):
    raw = response.read(limit)
    charset = response.headers.get_content_charset() or "utf-8"
    return raw.decode(charset, errors="replace")


def _cache_bust(url):
    parts = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
    query.append(("_wifi_retry", str(time.monotonic_ns())))
    return urllib.parse.urlunsplit(
        (parts.scheme, parts.netloc, parts.path, urllib.parse.urlencode(query), parts.fragment)
    )


def check_internet(timeout=CHECK_TIMEOUT):
    """Confirm Internet access without following a captive-portal redirect."""
    opener, _ = _new_session(no_redirect=True)
    for url in CHECK_URLS:
        try:
            with opener.open(_request(url), timeout=timeout) as response:
                status = response.getcode()
                final_url = response.geturl()
                if "gstatic.com" in url:
                    return status == 204 and "gstatic.com" in final_url
                body = response.read(64).decode("ascii", errors="ignore").strip()
                return (
                    status == 200
                    and "msftconnecttest.com" in final_url
                    and body == "Microsoft Connect Test"
                )
        except urllib.error.HTTPError as exc:
            if 300 <= exc.code < 400:
                return False
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError):
            continue
    return False


def _decode_js_string(token):
    """Decode a quoted JavaScript string, including hex/unicode/octal escapes."""
    if len(token) < 2 or token[0] not in {'"', "'"}:
        return token
    text = token[1:-1]
    result = []
    index = 0
    escapes = {
        "b": "\b",
        "f": "\f",
        "n": "\n",
        "r": "\r",
        "t": "\t",
        "v": "\v",
    }
    while index < len(text):
        char = text[index]
        if char != "\\":
            result.append(char)
            index += 1
            continue

        index += 1
        if index >= len(text):
            result.append("\\")
            break
        escaped = text[index]
        if escaped in {"\\", '"', "'", "/"}:
            result.append("/" if escaped == "/" else escaped)
            index += 1
        elif escaped in escapes:
            result.append(escapes[escaped])
            index += 1
        elif escaped == "x" and index + 2 < len(text):
            digits = text[index + 1 : index + 3]
            try:
                result.append(chr(int(digits, 16)))
                index += 3
            except ValueError:
                result.append("x")
                index += 1
        elif escaped == "u" and index + 4 < len(text):
            digits = text[index + 1 : index + 5]
            try:
                result.append(chr(int(digits, 16)))
                index += 5
            except ValueError:
                result.append("u")
                index += 1
        elif escaped in "01234567":
            end = index + 1
            while end < min(index + 3, len(text)) and text[end] in "01234567":
                end += 1
            result.append(chr(int(text[index:end], 8)))
            index = end
        elif escaped in "\r\n":
            if escaped == "\r" and index + 1 < len(text) and text[index + 1] == "\n":
                index += 1
            index += 1
        else:
            result.append(escaped)
            index += 1
    return "".join(result)


def _extract_balanced_object(text, brace_index):
    depth = 0
    quote = None
    escaped = False
    for index in range(brace_index, len(text)):
        char = text[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {'"', "'", "`"}:
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace_index : index + 1]
    return ""


def extract_wifi_info(html):
    """Safely parse the simple `const wifiInfo = {...}` object from MikroTik."""
    assignment = re.search(r"\b(?:const|let|var)\s+wifiInfo\s*=\s*", html, re.I)
    if not assignment:
        return None
    brace_index = html.find("{", assignment.end())
    if brace_index < 0:
        return None
    object_text = _extract_balanced_object(html, brace_index)
    if not object_text:
        return None

    quoted = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    key_token = rf"(?:{quoted}|[A-Za-z_$][\w$-]*)"
    value_token = rf"(?:{quoted}|true|false|null|-?\d+(?:\.\d+)?)"
    property_pattern = re.compile(
        rf"(?P<key>{key_token})\s*:\s*(?P<value>{value_token})",
        re.I | re.S,
    )

    result = {}
    for match in property_pattern.finditer(object_text):
        raw_key = match.group("key")
        raw_value = match.group("value")
        key = _decode_js_string(raw_key) if raw_key[0] in {'"', "'"} else raw_key
        key = key.strip().lower().replace("-", "_")
        if raw_value[0] in {'"', "'"}:
            value = _decode_js_string(raw_value)
        elif raw_value.lower() == "true":
            value = True
        elif raw_value.lower() == "false":
            value = False
        elif raw_value.lower() == "null":
            value = None
        else:
            value = raw_value
        result[key] = value
    return result or None


def _info_value(info, *names):
    for name in names:
        value = info.get(name.lower().replace("-", "_"))
        if value is not None and value != "":
            return value
    return ""


def _is_logged_in(info):
    value = str(_info_value(info, "logged_in", "logged-in", "authenticated")).lower()
    return value in {"yes", "true", "1", "logged in", "connected"}


def _discover_serial(html, info=None):
    if info:
        serial = str(_info_value(info, "serial", "router_serial", "gateway_serial"))
        if re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", serial, re.I):
            return serial.upper()

    decoded = urllib.parse.unquote(html)
    match = re.search(
        r"serial(?:=|[\"']?\s*:\s*[\"']?)\s*((?:[0-9A-F]{2}:){5}[0-9A-F]{2})",
        decoded,
        re.I,
    )
    return match.group(1).upper() if match else DEFAULT_SERIAL


def _to_url_octal(value):
    return "".join("\\" + format(ord(char), "03o") for char in str(value or ""))


def _choose_login_form(html, base_url):
    parser = LoginFormParser()
    parser.feed(html)
    candidates = [
        form
        for form in parser.forms
        if form["method"] == "post"
        and form["action"]
        and {"username", "password"}.issubset(form["fields"])
    ]
    if not candidates:
        return None
    form = next(
        (
            item
            for item in candidates
            if item["id"].lower() == "frmlogin"
            or item["name"].lower() == "mikrotik"
        ),
        candidates[0],
    )
    form["action"] = urllib.parse.urljoin(base_url, form["action"])
    return form


def _fetch_page(opener, url, timeout=REQUEST_TIMEOUT, referer=None):
    request = _request(url)
    if referer:
        request.add_header("Referer", referer)
    with opener.open(request, timeout=timeout) as response:
        return response.geturl(), _read_html(response)


def _gateway_status_reachable(cookie_jar):
    opener, _ = _new_session(cookie_jar, no_redirect=True)
    request = _request(GATEWAY_STATUS)
    request.add_header("Referer", GATEWAY_LOGIN)
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT) as response:
            return response.getcode() < 500
    except urllib.error.HTTPError as exc:
        return 300 <= exc.code < 500
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError):
        return False


def _logout_gateway(cookie_jar):
    opener, _ = _new_session(cookie_jar, no_redirect=True)
    request = _request(GATEWAY_LOGOUT)
    request.add_header("Referer", GATEWAY_STATUS)
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT):
            return True
    except urllib.error.HTTPError as exc:
        return 300 <= exc.code < 400
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError):
        return False


def _read_router_info(opener):
    final_url, html = _fetch_page(opener, GATEWAY_LOGIN)
    return extract_wifi_info(html), _discover_serial(html), final_url, html


def _acquire_router_context(recovery_state):
    """Get a fresh CHAP challenge; force one guarded logout if the session is stuck."""
    last_reason = "Router không trả về wifiInfo"
    opener, cookie_jar = _new_session()

    for phase in range(2):
        for attempt in range(ROUTER_READ_ATTEMPTS):
            try:
                info, serial, _, _ = _read_router_info(opener)
                if info is not None:
                    if not _is_logged_in(info):
                        return opener, cookie_jar, info, serial, ""
                    if check_internet(timeout=1.5):
                        return opener, cookie_jar, info, serial, "online"
                    last_reason = "Router báo đã đăng nhập nhưng Internet không hoạt động"
                    break
            except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
                last_reason = str(exc.reason if isinstance(exc, urllib.error.URLError) else exc)
            if attempt + 1 < ROUTER_READ_ATTEMPTS:
                time.sleep(0.35)

        if phase == 1 or recovery_state.get("logout_used"):
            break
        if check_internet(timeout=1.5):
            return opener, cookie_jar, {}, DEFAULT_SERIAL, "online"
        if not _gateway_status_reachable(cookie_jar):
            last_reason = "Không truy cập được trang status của gateway"
            break

        recovery_state["logout_used"] = True
        if not _logout_gateway(cookie_jar):
            last_reason = "Gateway không chấp nhận yêu cầu làm mới session"
            break
        _emit("Session MikroTik bị kẹt; đã làm mới và chờ challenge mới...", "warning")
        time.sleep(LOGOUT_WAIT_SECONDS)
        opener, cookie_jar = _new_session()

    return None, None, None, None, last_reason


def _build_awing_login_url(info, serial):
    mac = str(_info_value(info, "mac", "client_mac", "client-mac"))
    client_ip = str(_info_value(info, "ip", "client_ip", "client-ip"))
    chap_id = _info_value(info, "chap_id", "chap-id")
    chap_challenge = _info_value(info, "chap_challenge", "chap-challenge")
    missing = [
        name
        for name, value in (
            ("mac", mac),
            ("ip", client_ip),
            ("chap_id", chap_id),
            ("chap_challenge", chap_challenge),
        )
        if value in {None, ""}
    ]
    if missing:
        raise ValueError("wifiInfo thiếu: " + ", ".join(missing))

    query = urllib.parse.urlencode(
        {
            "serial": serial or DEFAULT_SERIAL,
            "client_mac": mac,
            "client_ip": client_ip,
            "userurl": AWING_SUCCESS_URL,
            "login_url": GATEWAY_LOGIN,
            "chap_id": _to_url_octal(chap_id),
            "chap_challenge": _to_url_octal(chap_challenge),
        },
        quote_via=urllib.parse.quote,
    )
    return f"{AWING_LOGIN_URL}?{query}"


def _verify_awing(awing_url):
    """Load the AWING shell and call VerifyUrl, automatically retrying like F5."""
    opener, cookie_jar = _new_session()
    last_reason = "AWING không trả về contentAuthenForm"
    origin = f"{urllib.parse.urlsplit(AWING_BASE_URL).scheme}://{urllib.parse.urlsplit(AWING_BASE_URL).netloc}"

    for attempt in range(AWING_REFRESH_ATTEMPTS):
        try:
            _fetch_page(opener, awing_url, referer=awing_url)
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
            last_reason = str(exc.reason if isinstance(exc, urllib.error.URLError) else exc)

        verify = _request(AWING_VERIFY_URL, data=b"", method="POST")
        verify.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        verify.add_header("X-Requested-With", "XMLHttpRequest")
        verify.add_header("Origin", origin)
        verify.add_header("Referer", awing_url)
        try:
            with opener.open(verify, timeout=REQUEST_TIMEOUT) as response:
                payload = json.loads(_read_html(response))
            captive_context = payload.get("captiveContext") or {}
            auth_html = captive_context.get("contentAuthenForm") or ""
            if isinstance(auth_html, str):
                form = _choose_login_form(auth_html, awing_url)
                if form is not None:
                    return form, payload, opener, cookie_jar, ""
        except (
            urllib.error.URLError,
            TimeoutError,
            socket.timeout,
            OSError,
            ValueError,
        ) as exc:
            last_reason = str(exc.reason if isinstance(exc, urllib.error.URLError) else exc)
        if attempt + 1 < AWING_REFRESH_ATTEMPTS:
            time.sleep(0.25)

    return None, None, opener, cookie_jar, last_reason


def _send_analytics_fallback(opener, awing_url, verify_payload):
    """Compatibility-only fallback; never used on the normal fast path."""
    current_dto = copy.deepcopy(verify_payload)
    if isinstance(current_dto.get("captiveContext"), dict):
        current_dto["captiveContext"]["userContext"] = {}
    origin = f"{urllib.parse.urlsplit(AWING_BASE_URL).scheme}://{urllib.parse.urlsplit(AWING_BASE_URL).netloc}"

    for analytic_type, view_index in (("View", 1), ("Click", 0)):
        body = json.dumps(
            {
                "captiveContextDTO": current_dto,
                "analyticType": analytic_type,
                "viewIndex": view_index,
            },
            separators=(",", ":"),
        ).encode("utf-8")
        request = _request(AWING_ANALYTIC_URL, data=body, method="POST")
        request.add_header("Content-Type", "application/json; charset=utf-8")
        request.add_header("Origin", origin)
        request.add_header("Referer", awing_url)
        with opener.open(request, timeout=REQUEST_TIMEOUT) as response:
            response_text = _read_html(response)
        if analytic_type == "View":
            try:
                updated = json.loads(response_text)
                if isinstance(updated, dict) and updated.get("token"):
                    current_dto = updated
            except ValueError:
                pass


def _submit_gateway_form(form, cookie_jar, referer):
    payload = urllib.parse.urlencode(form["fields"]).encode("utf-8")
    request = _request(form["action"], data=payload, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    request.add_header("Referer", referer)
    opener, _ = _new_session(cookie_jar, no_redirect=True)
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT):
            pass
    except urllib.error.HTTPError as exc:
        if not 300 <= exc.code < 400:
            return False, f"Gateway trả HTTP {exc.code}"
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
        return False, str(exc.reason if isinstance(exc, urllib.error.URLError) else exc)

    for _ in range(15):
        if check_internet(timeout=1.25):
            return True, ""
        time.sleep(0.2)
    return False, "Gateway đã nhận form nhưng chưa cấp Internet"


def router_api_login(recovery_state, use_analytics=False):
    router_opener, router_cookies, info, serial, reason = _acquire_router_context(
        recovery_state
    )
    if reason == "online":
        return True, ""
    if info is None:
        return False, reason

    try:
        awing_url = _build_awing_login_url(info, serial)
    except ValueError as exc:
        return False, str(exc)

    form, verify_payload, awing_opener, _, reason = _verify_awing(awing_url)
    if form is None:
        return False, reason

    if use_analytics:
        try:
            _send_analytics_fallback(awing_opener, awing_url, verify_payload)
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError, ValueError):
            # Analytics is optional; the valid fresh CHAP form remains the priority.
            pass

    _emit(
        "Đã lấy challenge mới qua API MikroTik/AWING"
        + (" (chế độ tương thích)." if use_analytics else "."),
        "info",
    )
    return _submit_gateway_form(form, router_cookies, awing_url)


def _load_dynamic_login_form(opener, page_url):
    parts = urllib.parse.urlsplit(page_url)
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        return None
    verify_url = urllib.parse.urlunsplit(
        (parts.scheme, parts.netloc, "/Home/VerifyUrl", "", "")
    )
    verify = _request(verify_url, data=b"", method="POST")
    verify.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    verify.add_header("X-Requested-With", "XMLHttpRequest")
    verify.add_header("Referer", page_url)
    with opener.open(verify, timeout=REQUEST_TIMEOUT) as response:
        payload = json.loads(_read_html(response))
    auth_html = (payload.get("captiveContext") or {}).get("contentAuthenForm") or ""
    return _choose_login_form(auth_html, page_url) if isinstance(auth_html, str) else None


def _refresh_generic_awing_form(opener, page_url, initial_html):
    current_url = page_url
    html = initial_html
    for attempt in range(AWING_REFRESH_ATTEMPTS):
        form = _choose_login_form(html, current_url)
        if form is not None:
            return form
        try:
            form = _load_dynamic_login_form(opener, current_url)
            if form is not None:
                return form
        except (
            urllib.error.URLError,
            TimeoutError,
            socket.timeout,
            OSError,
            ValueError,
        ):
            pass
        if attempt + 1 < AWING_REFRESH_ATTEMPTS:
            try:
                current_url, html = _fetch_page(opener, current_url, referer=current_url)
            except (urllib.error.URLError, TimeoutError, socket.timeout, OSError):
                pass
            time.sleep(0.2)
    return None


def generic_http_login():
    """Browser-free fallback for portals that do not expose wifiInfo."""
    opener, cookie_jar = _new_session()
    pages = []
    last_reason = "Không lấy được trang đăng nhập AWING"
    for probe in PORTAL_PROBE_URLS:
        probe_url = _cache_bust(probe) if "awifi.vn" in probe else probe
        try:
            final_url, html = _fetch_page(opener, probe_url)
            if check_internet(timeout=1.5):
                return True, ""
            parts = urllib.parse.urlsplit(final_url)
            pages.append(f"{parts.netloc}{parts.path or '/'}")
            form = _choose_login_form(html, final_url)
            is_awing_login = (
                "awing-captive.min.js" in html.lower()
                or parts.netloc.lower().endswith("awingconnect.vn")
            ) and parts.path.lower() in {"/", "/login", "/home/login"}
            if form is None and is_awing_login:
                form = _refresh_generic_awing_form(opener, final_url, html)
            if form is None:
                continue
            _emit("Đang dùng luồng HTTP dự phòng (không Chromium)...", "warning")
            return _submit_gateway_form(form, cookie_jar, final_url)
        except (
            urllib.error.URLError,
            TimeoutError,
            socket.timeout,
            OSError,
            ValueError,
        ) as exc:
            last_reason = str(exc.reason if isinstance(exc, urllib.error.URLError) else exc)
    if pages:
        last_reason += "; trang nhận được: " + ", ".join(dict.fromkeys(pages))
    return False, last_reason


_last_error_signature = ""
_last_error_time = 0.0


def _write_error_log(message):
    """Write failures only, and avoid flooding the log with identical retries."""
    global _last_error_signature, _last_error_time
    now = time.monotonic()
    if message == _last_error_signature and now - _last_error_time < 60:
        return
    _last_error_signature = message
    _last_error_time = now
    try:
        ERROR_LOG.parent.mkdir(parents=True, exist_ok=True)
        with ERROR_LOG.open("a", encoding="utf-8") as log:
            timestamp = datetime.now().astimezone().isoformat(timespec="seconds")
            log.write(f"[{timestamp}] {message}\n")
    except OSError:
        pass


def auto_login(analytics_fallback=ENABLE_ANALYTICS_FALLBACK):
    _emit("WiFi đang yêu cầu đăng nhập. Đang khôi phục kết nối...", "warning")
    started = time.monotonic()
    recovery_state = {"logout_used": False}
    reasons = []

    success, reason = router_api_login(recovery_state, use_analytics=False)
    if success:
        _emit(
            f"Đã có Internet sau {time.monotonic() - started:.1f} giây.",
            "success",
        )
        return True
    reasons.append("API: " + reason)

    if analytics_fallback:
        time.sleep(0.5)
        success, reason = router_api_login(recovery_state, use_analytics=True)
        if success:
            _emit(
                f"Đã có Internet sau {time.monotonic() - started:.1f} giây.",
                "success",
            )
            return True
        reasons.append("API tương thích: " + reason)

    success, reason = generic_http_login()
    if success:
        _emit(
            f"Đã có Internet sau {time.monotonic() - started:.1f} giây.",
            "success",
        )
        return True
    reasons.append("HTTP dự phòng: " + reason)

    summary = " | ".join(reasons)
    _emit("Chưa thể kết nối; tool sẽ tự thử lại sau.", "error")
    _emit(f"Chi tiết: {summary}", "error")
    _write_error_log(summary)
    return False


def diagnose():
    """Read-only diagnostics for the next on-campus test."""
    _emit("Chẩn đoán router/API (không logout và không gửi form)...", "info")
    opener, _ = _new_session()
    try:
        info, serial, final_url, _ = _read_router_info(opener)
        parts = urllib.parse.urlsplit(final_url)
        _emit(f"Gateway phản hồi: {parts.netloc}{parts.path}", "info")
        _emit(f"wifiInfo: {'có' if info else 'không có'}", "info")
        _emit(f"Serial nhận diện: {serial}", "info")
        if info:
            available = sorted(
                key
                for key in ("logged_in", "mac", "ip", "chap_id", "chap_challenge")
                if _info_value(info, key)
            )
            _emit("Trường hợp lệ: " + ", ".join(available), "info")
    except Exception as exc:
        _emit(f"Lỗi gateway: {exc}", "error")
    _emit(f"Internet hiện tại: {'có' if check_internet() else 'không'}", "info")


def parse_args():
    parser = argparse.ArgumentParser(description="ATCN Wifi - tự động kết nối WiFi KTX ĐHQG-HCM")
    parser.add_argument("--once", action="store_true", help="chạy một lần rồi thoát")
    parser.add_argument("--diagnose", action="store_true", help="chẩn đoán nhưng không đăng nhập")
    parser.add_argument(
        "--interval",
        type=float,
        default=MONITOR_INTERVAL,
        help="số giây giữa mỗi lần kiểm tra (mặc định: 5)",
    )
    parser.add_argument(
        "--no-analytics-fallback",
        action="store_true",
        help="không dùng analytic ngay cả khi đăng nhập trực tiếp thất bại",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    print("================================================")
    print("=== ATCN Wifi - Auto Connect Wifi KTX ĐHQGHCM ===")
    print("================================================")

    if args.diagnose:
        diagnose()
        return 0

    if check_internet():
        print("[OK] Internet đang hoạt động.")
    else:
        auto_login(analytics_fallback=not args.no_analytics_fallback)

    if args.once:
        return 0 if check_internet() else 1

    print(f"[i] Đang theo dõi mỗi {max(1, args.interval):g} giây. Bấm Ctrl+C để dừng.")
    try:
        while True:
            time.sleep(max(1, args.interval))
            if not check_internet():
                auto_login(analytics_fallback=not args.no_analytics_fallback)
    except KeyboardInterrupt:
        print("\n[i] Đã dừng tool.")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
