# Android changelog

## 1.2.0-beta.2

- Kiểm tra Internet thực tế qua HTTPS trên đúng Wi‑Fi; bỏ việc tin cờ `VALIDATED` cũ khi kết nối thủ công.
- Nhận diện lại khi truy cập gateway lỗi tạm thời; phân biệt cần đăng nhập với lỗi mạng/chưa xác định.
- Kiểm tra thích ứng 5/10/30 giây và lựa chọn Ưu tiên kết nối liên tục; kiểm tra ngay khi bật màn hình.
- Giữ khả năng Android dùng 4G/5G cho ứng dụng khác, báo hệ thống đánh giá lại Wi‑Fi khi cần.
- Giới hạn và hủy thao tác mạng, tránh đăng nhập chồng nhau hoặc giữ kết quả của mạng Wi‑Fi cũ.
- Giảm cập nhật thông báo/nhật ký giống nhau; thêm thời điểm, thời gian kiểm tra và mạng mặc định vào giao diện/chẩn đoán.
- Bổ sung kiểm thử chính sách mạng, lịch kiểm tra và xử lý HTTP; tiếp tục cần thử thực địa trên S24 Ultra tại KTX khu A.

Bản thử nghiệm dùng package `io.github.noobhero1508.atcnwifi.beta`, versionCode 121 và cùng chữ ký với beta.1. Không có cam kết số phần trăm hao pin, khôi phục trong 5 giây hoặc chuyển 5G không gián đoạn.
