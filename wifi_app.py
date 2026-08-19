"""Cross-platform desktop interface for ATCN Wifi.

The same source runs on Windows and macOS. Release binaries are built separately
because executable formats and signing rules differ between the two platforms.
"""

import os
import queue
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import messagebox, ttk

import atcn_wifi_core as wifi_core


APP_NAME = "ATCN Wifi"
APP_SUBTITLE = "Auto Connect Wifi KTX ĐHQGHCM"
APP_VERSION = "1.1.0"
MONITOR_SECONDS = 5
INSTANCE_PORT = 47831

COLORS = {
    "background": "#F3F6FA",
    "surface": "#FFFFFF",
    "primary": "#1565C0",
    "primary_dark": "#0D47A1",
    "text": "#172033",
    "muted": "#667085",
    "border": "#D9E2EF",
    "success": "#16A34A",
    "warning": "#D97706",
    "error": "#DC2626",
    "offline": "#94A3B8",
}

_instance_socket = None


def acquire_single_instance():
    """Use a localhost socket as a dependency-free cross-platform app lock."""
    global _instance_socket
    candidate = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        candidate.bind(("127.0.0.1", INSTANCE_PORT))
        candidate.listen(1)
    except OSError:
        candidate.close()
        return False
    _instance_socket = candidate
    return True


def open_path(path):
    """Open a file with the platform default application."""
    if sys.platform == "win32":
        os.startfile(path)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])


def resource_path(relative_path):
    """Resolve a bundled PyInstaller resource or a source-tree resource."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative_path


class ATCNWifiApp:
    def __init__(self, autostart=True):
        self.autostart = autostart
        self.root = tk.Tk()
        self.root.title(f"{APP_NAME} - {APP_SUBTITLE}")
        self.root.geometry("820x640")
        self.root.minsize(680, 520)
        self.root.configure(bg=COLORS["background"])
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self._window_icon = None
        try:
            self._window_icon = tk.PhotoImage(file=resource_path("assets/icon-preview.png"))
            self.root.iconphoto(True, self._window_icon)
        except tk.TclError:
            pass

        self.events = queue.Queue()
        self.log_lines = []
        self.shutdown_event = threading.Event()
        self.monitor_enabled = threading.Event()
        self.connection_lock = threading.Lock()
        self.monitor_thread = None
        self.last_network_state = None

        self.status_text = tk.StringVar(value="●  Đang khởi tạo...")
        self.detail_text = tk.StringVar(value="Đang chuẩn bị kiểm tra kết nối mạng.")
        self.badge_text = tk.StringVar(value="THEO DÕI: TẮT")
        self.monitor_button_text = tk.StringVar(value="Dừng theo dõi")

        self._configure_styles()
        self._build_interface()
        wifi_core.set_event_callback(self._on_core_event)
        self._append_event("Ứng dụng đã sẵn sàng.", "info")
        self.root.after(80, self._drain_events)

    def _configure_styles(self):
        style = ttk.Style(self.root)
        available = style.theme_names()
        preferred = "vista" if sys.platform == "win32" else "aqua"
        if preferred in available:
            style.theme_use(preferred)

        style.configure(
            "ATCN.TButton",
            font=("TkDefaultFont", 11),
            padding=(14, 10),
        )
        style.configure(
            "ATCN.Primary.TButton",
            font=("TkDefaultFont", 11, "bold"),
            padding=(14, 10),
        )

    def _build_interface(self):
        self.root.grid_rowconfigure(1, weight=1)
        self.root.grid_columnconfigure(0, weight=1)

        header = tk.Frame(self.root, bg=COLORS["primary"], height=92)
        header.grid(row=0, column=0, sticky="ew")
        header.grid_propagate(False)
        header.grid_columnconfigure(0, weight=1)
        tk.Label(
            header,
            text=APP_NAME,
            bg=COLORS["primary"],
            fg="white",
            font=("TkDefaultFont", 22, "bold"),
            anchor="w",
        ).grid(row=0, column=0, sticky="ew", padx=26, pady=(16, 0))
        tk.Label(
            header,
            text=f"{APP_SUBTITLE}  ·  Không sử dụng trình duyệt",
            bg=COLORS["primary"],
            fg="#E7F0FF",
            font=("TkDefaultFont", 10),
            anchor="w",
        ).grid(row=1, column=0, sticky="ew", padx=27, pady=(2, 14))

        body = tk.Frame(self.root, bg=COLORS["background"])
        body.grid(row=1, column=0, sticky="nsew", padx=20, pady=(18, 14))
        body.grid_columnconfigure(0, weight=1)
        body.grid_rowconfigure(3, weight=1)

        status_card = tk.Frame(
            body,
            bg=COLORS["surface"],
            highlightbackground=COLORS["border"],
            highlightthickness=1,
        )
        status_card.grid(row=0, column=0, sticky="ew")
        status_card.grid_columnconfigure(0, weight=1)
        self.status_label = tk.Label(
            status_card,
            textvariable=self.status_text,
            bg=COLORS["surface"],
            fg=COLORS["offline"],
            font=("TkDefaultFont", 16, "bold"),
            anchor="w",
        )
        self.status_label.grid(row=0, column=0, sticky="ew", padx=(24, 10), pady=(18, 3))
        self.badge_label = tk.Label(
            status_card,
            textvariable=self.badge_text,
            bg="#EAF2FD",
            fg=COLORS["primary_dark"],
            font=("TkDefaultFont", 9, "bold"),
            padx=10,
            pady=5,
        )
        self.badge_label.grid(row=0, column=1, sticky="e", padx=(10, 22), pady=(18, 3))
        tk.Label(
            status_card,
            textvariable=self.detail_text,
            bg=COLORS["surface"],
            fg=COLORS["text"],
            font=("TkDefaultFont", 10),
            anchor="w",
        ).grid(row=1, column=0, columnspan=2, sticky="ew", padx=27, pady=(2, 19))

        actions = tk.Frame(body, bg=COLORS["background"])
        actions.grid(row=1, column=0, sticky="ew", pady=(14, 15))
        for column in range(4):
            actions.grid_columnconfigure(column, weight=1, uniform="actions")

        self.connect_button = ttk.Button(
            actions,
            text="Kết nối ngay",
            command=self.connect_now,
            style="ATCN.Primary.TButton",
        )
        self.connect_button.grid(row=0, column=0, sticky="ew", padx=(0, 6))
        self.monitor_button = ttk.Button(
            actions,
            textvariable=self.monitor_button_text,
            command=self.toggle_monitoring,
            style="ATCN.TButton",
        )
        self.monitor_button.grid(row=0, column=1, sticky="ew", padx=6)
        self.diagnose_button = ttk.Button(
            actions,
            text="Chẩn đoán",
            command=self.run_diagnostics,
            style="ATCN.TButton",
        )
        self.diagnose_button.grid(row=0, column=2, sticky="ew", padx=6)
        ttk.Button(
            actions,
            text="Nhật ký lỗi",
            command=self.open_error_log,
            style="ATCN.TButton",
        ).grid(row=0, column=3, sticky="ew", padx=(6, 0))

        tk.Label(
            body,
            text="Hoạt động gần đây",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("TkDefaultFont", 12, "bold"),
            anchor="w",
        ).grid(row=2, column=0, sticky="ew", padx=2, pady=(0, 7))

        log_frame = tk.Frame(
            body,
            bg=COLORS["surface"],
            highlightbackground=COLORS["border"],
            highlightthickness=1,
        )
        log_frame.grid(row=3, column=0, sticky="nsew")
        log_frame.grid_rowconfigure(0, weight=1)
        log_frame.grid_columnconfigure(0, weight=1)
        self.event_log = tk.Text(
            log_frame,
            wrap="word",
            relief="flat",
            borderwidth=0,
            bg=COLORS["surface"],
            fg=COLORS["text"],
            insertbackground=COLORS["text"],
            font=("TkFixedFont", 10),
            padx=12,
            pady=10,
            state="disabled",
        )
        scrollbar = ttk.Scrollbar(log_frame, orient="vertical", command=self.event_log.yview)
        self.event_log.configure(yscrollcommand=scrollbar.set)
        self.event_log.grid(row=0, column=0, sticky="nsew")
        scrollbar.grid(row=0, column=1, sticky="ns")

        footer = tk.Frame(body, bg=COLORS["background"])
        footer.grid(row=4, column=0, sticky="ew", pady=(10, 0))
        footer.grid_columnconfigure(1, weight=1)
        tk.Label(
            footer,
            text=f"Phiên bản {APP_VERSION}",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("TkDefaultFont", 9),
        ).grid(row=0, column=0, sticky="w")
        tk.Label(
            footer,
            text=f"Kiểm tra mỗi {MONITOR_SECONDS} giây",
            bg=COLORS["background"],
            fg=COLORS["muted"],
            font=("TkDefaultFont", 9),
        ).grid(row=0, column=2, sticky="e")

    def _post(self, event):
        self.events.put(event)

    def _on_core_event(self, message, level):
        self._post(("event", message, level))

    def _append_event(self, message, level="info"):
        prefixes = {"success": "✓", "warning": "!", "error": "×", "info": "·"}
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_lines.append(f"[{timestamp}] {prefixes.get(level, '·')} {message}")
        self.log_lines = self.log_lines[-300:]
        self.event_log.configure(state="normal")
        self.event_log.delete("1.0", "end")
        self.event_log.insert("end", "\n".join(self.log_lines))
        self.event_log.see("end")
        self.event_log.configure(state="disabled")

    def _set_status(self, state, detail):
        states = {
            "online": ("●  Đã kết nối Internet", COLORS["success"]),
            "offline": ("●  Chưa có Internet", COLORS["error"]),
            "connecting": ("●  Đang kết nối lại...", COLORS["warning"]),
            "checking": ("●  Đang kiểm tra...", COLORS["primary"]),
            "paused": ("●  Đã dừng theo dõi", COLORS["offline"]),
        }
        label, color = states.get(state, states["checking"])
        self.status_text.set(label)
        self.detail_text.set(detail)
        self.status_label.configure(fg=color)

    def _drain_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                if event[0] == "event":
                    self._append_event(event[1], event[2])
                elif event[0] == "status":
                    self._set_status(event[1], event[2])
                elif event[0] == "monitor":
                    enabled = event[1]
                    self.badge_text.set("THEO DÕI: BẬT" if enabled else "THEO DÕI: TẮT")
                    self.monitor_button_text.set(
                        "Dừng theo dõi" if enabled else "Bắt đầu theo dõi"
                    )
                    self.badge_label.configure(
                        bg="#EAF2FD" if enabled else "#EEF1F5",
                        fg=COLORS["primary_dark"] if enabled else COLORS["muted"],
                    )
                elif event[0] == "busy":
                    state = "disabled" if event[1] else "normal"
                    self.connect_button.configure(state=state)
                    self.diagnose_button.configure(state=state)
        except queue.Empty:
            pass
        if not self.shutdown_event.is_set():
            self.root.after(80, self._drain_events)

    def _wait_interruptibly(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline and not self.shutdown_event.is_set():
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))

    def _monitor_loop(self):
        while not self.shutdown_event.is_set():
            if not self.monitor_enabled.is_set():
                self._wait_interruptibly(0.3)
                continue
            self._post(("status", "checking", "Đang xác minh quyền truy cập Internet."))
            online = wifi_core.check_internet()
            if online:
                self._post(("status", "online", "Kết nối đang hoạt động bình thường."))
                if self.last_network_state is not True:
                    self._post(("event", "Internet đang hoạt động.", "success"))
                self.last_network_state = True
            else:
                self._post(("status", "offline", "Captive portal đang chặn kết nối."))
                if self.last_network_state is not False:
                    self._post(("event", "Phát hiện mất quyền truy cập Internet.", "warning"))
                self.last_network_state = False
                self._connect_worker()
            self._wait_interruptibly(MONITOR_SECONDS)

    def _connect_worker(self):
        if not self.connection_lock.acquire(blocking=False):
            return
        self._post(("busy", True))
        try:
            if wifi_core.check_internet(timeout=1.5):
                self._post(("status", "online", "Kết nối đang hoạt động bình thường."))
                self._post(
                    ("event", "Internet đã được kết nối; không cần đăng nhập lại.", "success")
                )
                self.last_network_state = True
                return
            self._post(
                ("status", "connecting", "Đang lấy challenge và xác thực với gateway.")
            )
            success = wifi_core.auto_login()
            if success:
                self._post(("status", "online", "Đã khôi phục quyền truy cập Internet."))
                self.last_network_state = True
            else:
                self._post(("status", "offline", "Chưa thể kết nối; sẽ tự thử lại."))
                self.last_network_state = False
        finally:
            self._post(("busy", False))
            self.connection_lock.release()

    def connect_now(self):
        threading.Thread(target=self._connect_worker, daemon=True).start()

    def start_monitoring(self):
        self.monitor_enabled.set()
        self._post(("monitor", True))
        self._post(("event", "Đã bật theo dõi tự động.", "info"))
        if self.monitor_thread is None or not self.monitor_thread.is_alive():
            self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
            self.monitor_thread.start()

    def stop_monitoring(self):
        self.monitor_enabled.clear()
        self._post(("monitor", False))
        self._post(("status", "paused", "Nhấn Bắt đầu theo dõi để tiếp tục."))
        self._post(("event", "Đã dừng theo dõi tự động.", "warning"))

    def toggle_monitoring(self):
        if self.monitor_enabled.is_set():
            self.stop_monitoring()
        else:
            self.start_monitoring()

    def run_diagnostics(self):
        def worker():
            if not self.connection_lock.acquire(blocking=False):
                self._post(("event", "Một tiến trình kết nối đang chạy.", "warning"))
                return
            self._post(("busy", True))
            try:
                wifi_core.diagnose()
            finally:
                self._post(("busy", False))
                self.connection_lock.release()

        threading.Thread(target=worker, daemon=True).start()

    def open_error_log(self):
        path = wifi_core.ERROR_LOG
        if not path.exists():
            messagebox.showinfo(APP_NAME, "Chưa có lỗi nào được ghi lại.", parent=self.root)
            return
        try:
            open_path(path)
        except OSError as exc:
            messagebox.showerror(
                APP_NAME,
                f"Không thể mở nhật ký lỗi:\n{exc}",
                parent=self.root,
            )

    def close(self):
        self.shutdown_event.set()
        self.monitor_enabled.clear()
        wifi_core.set_event_callback(None)
        self.root.destroy()

    def run(self):
        if self.autostart:
            self.start_monitoring()
        self.root.mainloop()
        return 0


def smoke_test():
    """Headless packaging check suitable for Windows and macOS CI runners."""
    assert APP_NAME == "ATCN Wifi"
    assert callable(wifi_core.check_internet)
    assert wifi_core.ERROR_LOG.name == "wifi_errors.log"
    print(f"gui_smoke_test=PASS platform={sys.platform} version={APP_VERSION}")
    return 0


def main():
    if "--smoke-test" in sys.argv:
        return smoke_test()
    if not acquire_single_instance():
        temporary_root = tk.Tk()
        temporary_root.withdraw()
        messagebox.showinfo(APP_NAME, "Ứng dụng đang chạy trong một cửa sổ khác.")
        temporary_root.destroy()
        return 0
    return ATCNWifiApp().run()


if __name__ == "__main__":
    raise SystemExit(main())
