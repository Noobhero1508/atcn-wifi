# ATCN Wifi cho Android

Ứng dụng native Kotlin/Jetpack Compose giúp điện thoại đang kết nối Wi‑Fi KTX ĐHQGHCM tự đăng nhập lại captive portal AWING/MikroTik mà không mở WebView hay trình duyệt.

Trạng thái hiện tại: **beta kiểm thử KTX khu A**. Hỗ trợ Android 8–16 (`minSdk 26`, `targetSdk 36`), ưu tiên Samsung S24 Ultra Android 16. KTX khu B chưa được xác nhận thực địa.

## Cách hoạt động

1. Người dùng tự chọn và kết nối Wi‑Fi KTX trong Android.
2. Ứng dụng lấy đúng đối tượng `Network` Wi‑Fi, không chuyển toàn bộ tiến trình sang Wi‑Fi và không để request portal chạy nhầm qua 4G/5G.
3. Ứng dụng kiểm tra Internet bằng yêu cầu HTTPS nhỏ có phản hồi xác định; trạng thái Android chỉ là tín hiệu để kiểm tra. Phản hồi HTTP của captive portal hoặc một lỗi DNS/timeout không đủ để kết luận Internet hoạt động hay phiên hết hạn.
4. Khi bị captive portal, ứng dụng đọc `wifiInfo` mới từ MikroTik, tạo URL AWING chứa CHAP challenge của chính phiên hiện tại, gọi `VerifyUrl`, lấy form ẩn rồi gửi thẳng form về gateway.
5. Nếu portal bị kẹt giống trường hợp cần F5, ứng dụng cache-bust và thử làm mới tối đa ba lần. Logout chỉ được dùng một lần có điều kiện khi router báo phiên bị kẹt.
6. Chế độ tương thích View/Click mặc định tắt và chỉ chạy khi người dùng chủ động bật.

Không có username/password tĩnh, Selenium, Chromium hay WebView.

## Tự động theo dõi và pin

- `NetworkCallback` xử lý ngay khi Android báo Wi‑Fi hoặc trạng thái captive portal thay đổi.
- Vòng kiểm tra thực tế chỉ bắt đầu sau khi nhận diện đúng gateway KTX; lỗi nhận diện tạm thời sẽ được thử lại thay vì ghi nhớ mãi.
- Chế độ cân bằng: màn hình sáng 5 giây; tắt màn hình dưới 2 phút 10 giây, từ 2 phút trở lên 30 giây. Bật màn hình kiểm tra ngay. Thay đổi cài đặt áp dụng cho dịch vụ đang chạy.
- **Ưu tiên kết nối liên tục** giữ chu kỳ dự kiến 5 giây kể cả khi tắt màn hình; phù hợp lúc gọi/nghe nhạc, đổi lại hao pin hơn. Mặc định tắt.
- Khi đăng nhập thất bại, retry theo nhịp 2, 5, 10, 30 và 60 giây thay vì tạo request liên tục.
- Mỗi lần đăng nhập có giới hạn thời gian 35 giây; wake lock chỉ giữ ngắn hạn trong thao tác mạng, được giải phóng khi kết thúc hoặc hủy.
- Foreground service có thông báo cố định và chỉ chạy sau khi người dùng bật. Không có boot receiver, vì vậy sau khi khởi động lại máy người dùng phải mở ứng dụng thủ công.

Samsung có thể tự đưa ứng dụng vào chế độ ngủ. Nếu theo dõi bị dừng khi tắt màn hình, vào **Settings → Apps → ATCN Wifi → Battery → Unrestricted**.

Foreground service không bảo đảm kiểm tra đúng mỗi 5/10/30 giây khi máy vào Doze. Không giữ CPU thức liên tục; thời gian khôi phục còn phụ thuộc hệ thống, phản hồi router và AWING. Chưa công bố số phần trăm hao pin vì cần đo đối chứng trên thiết bị thật.

## 4G/5G trong lúc Wi‑Fi lỗi

App chỉ buộc yêu cầu kiểm tra/đăng nhập vào Wi‑Fi, không đổi mạng của cả điện thoại. Android có thể chọn dữ liệu di động cho các ứng dụng khác trong lúc ATCN Wifi phục hồi AWING. Khi Wi‑Fi mất Internet được xác nhận, app báo hệ thống đánh giá lại; quyết định chuyển mạng vẫn thuộc Android.

Trên Samsung, giữ dữ liệu di động bật và kiểm tra **Wi‑Fi → ⋮ → Intelligent Wi‑Fi → Switch to mobile data** (tùy máy/nhà mạng). Nếu hệ thống ngắt hẳn Wi‑Fi, app chờ Wi‑Fi nối lại. Không bảo đảm cuộc gọi không gián đoạn khi đổi mạng.

Trang chủ hiển thị riêng trạng thái Wi‑Fi KTX và mạng mặc định Android đang chọn. Nhật ký xuất kèm thời điểm, chu kỳ và thời gian kiểm tra; không chứa bí mật phiên đăng nhập.

## Cài bản thử nghiệm mới

Tải APK và SHA256 tại [Android 1.2.0-beta.2](https://github.com/Noobhero1508/atcn-wifi/releases/tag/android-v1.2.0-beta.2), cho phép cài ứng dụng từ nguồn bạn dùng để tải, rồi mở APK. Bản chính thức trong release này dùng cùng package `.beta` và chữ ký với beta.1, có thể cài cập nhật mà không xóa dữ liệu.

APK tự build hoặc artifact CI có thể dùng khóa debug khác; khi chữ ký khác Android sẽ từ chối cài đè. Không gỡ bản đang dùng trước khi xuất nhật ký/cài đặt cần giữ.

## Cấu trúc mã nguồn

| Khu vực | Vai trò |
|---|---|
| `core/` | HTTP session gắn với Wi‑Fi, parser an toàn, luồng AWING/MikroTik và redaction |
| `monitor/` | Foreground service, NetworkCallback, retry/backoff, log cục bộ và preferences |
| `ui/` | Giao diện Material 3, trạng thái, kết nối thủ công, chẩn đoán, nhật ký và cài đặt |
| `src/test/` | Unit test parser CHAP/form/JSON, allowlist và lọc dữ liệu nhạy cảm |

## Build và kiểm tra

Yêu cầu:

- JDK 17
- Android SDK Platform 36 và Build Tools 36.0.0

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\duong-dan-toi\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat testDebugUnitTest lintBeta assembleBeta assembleDebugAndroidTest
```

macOS/Linux:

```bash
cd android
./gradlew testDebugUnitTest lintBeta assembleBeta assembleDebugAndroidTest
```

APK nằm tại:

```text
android/app/build/outputs/apk/beta/app-beta.apk
```

Cài qua ADB:

```bash
adb install -r android/app/build/outputs/apk/beta/app-beta.apk
```

Bản beta dùng R8 để thu gọn mã/tài nguyên, ký bằng khóa debug hiện có, với application ID `io.github.noobhero1508.atcnwifi.beta`, tách biệt với bản release tương lai. Variant `debug` vẫn dùng cho phát triển và kiểm thử giao diện.

## Checklist kiểm thử thực địa

1. Kết nối Wi‑Fi KTX khu A và giữ 4G/5G bật để xác nhận request vẫn đi đúng Wi‑Fi.
2. Mở ứng dụng, nhấn **Kết nối ngay** và xác nhận Internet hoạt động mà không xuất hiện trình duyệt.
3. Chạy **Chẩn đoán**, kiểm tra log không chứa MAC, IP, CHAP, cookie, token hay password.
4. Bật **Tự động theo dõi**, tắt màn hình rồi chờ portal hết phiên; xác nhận ứng dụng tự khôi phục.
5. Mô phỏng portal load lỗi nếu có thể; xác nhận ứng dụng refresh/retry thay vì lặp vô hạn.
6. Thử từ chối quyền thông báo trên Android 13+; service vẫn chạy và xuất hiện trong mục **Active apps** của Android.
7. Nếu cách chuẩn thất bại, bật **Chế độ tương thích AWING** rồi lặp lại; lưu log đã xuất để sửa đúng nguyên nhân.
8. Khi Wi‑Fi vẫn báo kết nối nhưng Internet bị cắt, thử **Kết nối ngay**; xác nhận app không báo online chỉ vì cờ `VALIDATED` cũ.
9. Quan sát chu kỳ 5 → 10 → 30 giây khi tắt màn hình; bật lại phải kiểm tra ngay. Bật Ưu tiên liên tục để giữ chu kỳ dự kiến 5 giây.
10. Trong lúc 4G/5G đang là mạng mặc định, xác nhận Wi‑Fi KTX không bị báo online nhờ dữ liệu di động.
11. Thử mất đường Internet nhưng phiên router vẫn hợp lệ; không được logout liên tục. Đổi Wi‑Fi/bấm Dừng giữa thao tác để kiểm tra tác vụ cũ bị hủy.

### Đo pin

So sánh không theo dõi, ưu tiên liên tục và cân bằng trong các khoảng 6–8 giờ; lặp lại với mức pin, màn hình, nhiệt độ, sóng, dữ liệu di động và các tác vụ tương đương. Ghi lại cả số lần đăng nhập và thời gian dùng 4G/5G để tránh so sánh các tình trạng mạng khác nhau. Báo mức tăng bằng **điểm phần trăm pin mỗi giờ**, không nhầm với phần trăm sử dụng của app trong màn hình thống kê pin.

## Bảo mật và quyền riêng tư

- Cleartext HTTP bị tắt mặc định và chỉ mở cho đúng gateway/host kiểm tra được khai báo trong `network_security_config.xml`.
- Form chỉ được gửi tới allowlist `186.186.0.1` hoặc `v1.awingconnect.vn`.
- Redirect khác origin bị loại bỏ `Authorization`, `Origin` và `Referer`.
- Nhật ký lọc query string, MAC, IP, CHAP, username/password, cookie, token và `sessionId` trước khi ghi.
- Không có tài khoản, quảng cáo, telemetry riêng hay sao lưu cloud dữ liệu ứng dụng.

Android 16/target SDK 36 vẫn cho phép request trực tiếp tới gateway bằng quyền `INTERNET`. Khi nâng target lên Android 17/API 37, cần bổ sung và xin runtime permission `ACCESS_LOCAL_NETWORK`; không khai báo sớm permission này cho bản target 36.

## Ký APK trước khi phát hành

APK debug hiện tại chỉ dùng để test. Trước khi public rộng rãi cần tạo **một khóa release duy nhất**, giữ file `.jks` và mật khẩu ngoài Git, đồng thời sao lưu ít nhất hai nơi an toàn. Mọi bản cập nhật sau này phải dùng đúng khóa đó; mất khóa có thể khiến người dùng không thể cập nhật ứng dụng đã cài.

Không commit `.jks`, `.keystore`, `local.properties`, APK hoặc mật khẩu. Các mẫu này đã được thêm vào `.gitignore`.
