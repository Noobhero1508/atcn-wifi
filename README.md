# ATCN Wifi (hiện chỉ hoạt động với "Free Wi-MESH - Rescue" tại KTX khu A)

**Auto Connect Wifi KTX ĐHQGHCM** — tự động khôi phục kết nối Internet trên captive portal AWING/MikroTik mà không mở Chromium hay trình duyệt ẩn. Dự án gồm ứng dụng desktop cho Windows/macOS và ứng dụng Android native.

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

## Android beta

Tải [APK Android 1.2.0-beta.2](https://github.com/Noobhero1508/atcn-wifi/releases/tag/android-v1.2.0-beta.2). Ứng dụng nằm trong thư mục [`android`](android/README.md), hỗ trợ Android 8 đến Android 16, ưu tiên kiểm thử trên Samsung S24 Ultra/Android 16:

- Người dùng tự kết nối Wi‑Fi trước; ứng dụng chỉ nhận diện mạng KTX và đăng nhập AWING.
- Có **Kết nối ngay** và **Tự động theo dõi** do người dùng bật/tắt; không tự chạy khi khởi động lại điện thoại.
- Chế độ cân bằng: màn hình sáng 5 giây, tắt màn hình dưới 2 phút 10 giây, sau đó 30 giây; bật màn hình kiểm tra ngay. Có **Ưu tiên kết nối liên tục** với chu kỳ dự kiến 5 giây.
- Kiểm tra Internet thực tế qua Wi‑Fi; không chỉ tin trạng thái kết nối của Android. Máy ngủ sâu có thể trì hoãn lịch kiểm tra.
- Mọi request portal đi qua đúng `Network` Wi‑Fi; Android vẫn có thể chọn 4G/5G cho các ứng dụng khác. Chuyển mạng không được bảo đảm tức thì hoặc không gián đoạn.
- Hiển thị riêng Wi‑Fi KTX, mạng Android đang chọn, thời điểm và thời gian kiểm tra gần nhất.
- Giao diện tiếng Việt/Anh theo ngôn ngữ hệ thống; nhật ký được lọc bí mật và lưu cục bộ.

APK này là bản **thử nghiệm**, dùng cùng khóa debug với beta.1 để cài cập nhật; chữ ký release lâu dài sẽ được thiết lập riêng trước bản ổn định. Chưa có số đo hao pin hay xác nhận khôi phục phiên trên S24 Ultra tại KTX cho beta.2. Xem [hướng dẫn Android](android/README.md) và [thay đổi phiên bản](android/CHANGELOG.md).

## Tính năng

- Một mã nguồn giao diện desktop cho Windows và macOS; Android dùng Kotlin/Jetpack Compose native.
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

Android beta được phát hành riêng tại [android-v1.2.0-beta.2](https://github.com/Noobhero1508/atcn-wifi/releases/tag/android-v1.2.0-beta.2), không thay thế bản desktop trong đường dẫn `releases/latest`.

## Chạy từ mã nguồn

### Windows/macOS

Yêu cầu Python 3.10 trở lên có Tk 8.6.

```bash
python wifi_app.py
```

Chế độ dòng lệnh:

```bash
python atcn_wifi_core.py --once
python atcn_wifi_core.py --diagnose
```

### Android

Yêu cầu JDK 17 và Android SDK 36:

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

APK debug được tạo tại `android/app/build/outputs/apk/debug/app-debug.apk`.

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

CI cũng biên dịch, chạy unit test và Android Lint cho APK. Trước khi đưa Android vào GitHub Releases cần tạo khóa ký release riêng, sao lưu khóa an toàn và cấu hình GitHub Secrets; không commit file `.jks` hay mật khẩu vào repository.

## Quyền riêng tư

Ứng dụng chỉ gửi request đến gateway MikroTik, AWING và các URL kiểm tra kết nối. Không có máy chủ telemetry riêng và không thu thập dữ liệu người dùng. Khi luồng trực tiếp thất bại, chế độ tương thích có thể gửi sự kiện `View`/`Click` đến chính AWING trước khi thử lại form đăng nhập.

## Giấy phép

[MIT](LICENSE)
