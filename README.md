# ATCN Wifi

**Auto Connect Wifi KTX ĐHQGHCM** — tự động khôi phục kết nối Internet trên captive portal AWING/MikroTik mà không mở Chromium hay trình duyệt ẩn.

> Trạng thái: beta. Luồng hiện đã được kiểm thử với cấu hình KTX khu A; khu B cần thêm kiểm thử thực tế.

## Cài đặt bằng một lệnh

### Windows 10/11

Mở PowerShell và chạy:

```powershell
irm https://raw.githubusercontent.com/Noobhero1508/atcn-wifi/main/install.ps1 | iex
```

Bộ cài tải bản phát hành mới nhất, xác minh SHA256, cài vào `%LOCALAPPDATA%\ATCN Wifi` và tạo lối tắt trong Start Menu.

### macOS

Mở Terminal và chạy:

```bash
curl -fsSL https://raw.githubusercontent.com/Noobhero1508/atcn-wifi/main/install.sh | bash
```

Script tự nhận diện Apple Silicon hoặc Intel, xác minh SHA256 rồi cài ứng dụng vào `~/Applications` mà không cần `sudo`.

Do bản phát hành chưa được Apple ký và notarize, lần mở đầu macOS có thể chặn ứng dụng. Hãy bấm chuột phải vào **ATCN Wifi.app** → **Open** → **Open**. Windows SmartScreen cũng có thể cảnh báo tương tự với bản EXE chưa ký số.

## Tính năng

- Một mã nguồn giao diện cho Windows và macOS.
- Tự kiểm tra kết nối mỗi 5 giây và đăng nhập lại khi captive portal chặn mạng.
- Gọi trực tiếp API AWING/MikroTik; không dùng Chromium, Selenium hoặc Playwright.
- Tự làm mới khi portal trả trang trắng tương tự thao tác F5.
- Chế độ kết nối ngay, tạm dừng theo dõi, chẩn đoán và nhật ký lỗi.
- Không lưu username/password tĩnh; CHAP challenge được lấy mới theo phiên.
- Chỉ ghi log khi có lỗi và lưu tại thư mục dữ liệu của người dùng.

## Tải thủ công

Trong mục [GitHub Releases](https://github.com/Noobhero1508/atcn-wifi/releases/latest):

- `ATCN-Wifi-Windows-x64.exe`: Windows 10/11 64-bit.
- `ATCN-Wifi-macOS-arm64.dmg`: Mac Apple Silicon.
- `ATCN-Wifi-macOS-x64.dmg`: Mac Intel.
- Các file `.sha256`: checksum tương ứng.

## Chạy từ mã nguồn

Yêu cầu Python 3.10 trở lên có Tk 8.6.

```bash
python wifi_app.py
```

Chế độ dòng lệnh:

```bash
python atcn_wifi_core.py --once
python atcn_wifi_core.py --diagnose
```

## Phát hành phiên bản mới

GitHub Actions kiểm thử trên Windows và macOS. Khi đẩy tag `v*`, workflow tự build ba bộ cài, tạo checksum rồi tạo GitHub Release:

```bash
git tag v1.1.0
git push origin v1.1.0
```

Build thủ công:

```powershell
python -m pip install -r requirements-dev.txt
powershell -File scripts/build_windows.ps1
```

```bash
python3 -m pip install -r requirements-dev.txt
bash scripts/build_macos.sh arm64
```

## Quyền riêng tư

Ứng dụng chỉ gửi request đến gateway MikroTik, AWING và các URL kiểm tra kết nối. Không có máy chủ telemetry riêng và không thu thập dữ liệu người dùng. Khi luồng trực tiếp thất bại, chế độ tương thích có thể gửi sự kiện `View`/`Click` đến chính AWING trước khi thử lại form đăng nhập.

## Giấy phép

[MIT](LICENSE)
