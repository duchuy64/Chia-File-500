# Chia File 500 - Android

App Android đơn giản để chia file trong một thư mục thành nhiều thư mục nhỏ.

Ví dụ thư mục `A` có 1.240 file và giới hạn là 500 file/thư mục:

- `A - P1`: 500 file
- `A - P2`: 500 file
- `A - P3`: 240 file

File được **di chuyển**, không sao chép. App chỉ xử lý các file nằm trực tiếp trong thư mục đã chọn, không xử lý các thư mục con có sẵn.

## Cách lấy APK mà không cần Android Studio

### Bước 1 - Tạo repository GitHub

1. Đăng nhập GitHub.
2. Chọn **New repository**.
3. Đặt tên, ví dụ `Chia-File-500`.
4. Chọn Public hoặc Private đều được.
5. Bấm **Create repository**.

### Bước 2 - Upload project này

Upload **toàn bộ nội dung bên trong thư mục project này** lên repository.

Quan trọng: ở trang chính của repository phải nhìn thấy trực tiếp các mục sau:

- `.github`
- `app`
- `build.gradle`
- `settings.gradle`
- `gradle.properties`

Không để chúng bị lồng thêm một cấp thư mục.

### Bước 3 - Build tự động

Sau khi upload xong:

1. Vào tab **Actions** trên GitHub.
2. Chọn workflow **Build Android APK**.
3. Nếu workflow chưa tự chạy, bấm **Run workflow**.
4. Mở lần chạy mới nhất.
5. Khi tất cả bước có dấu tích xanh, kéo xuống phần **Artifacts**.
6. Tải file **Chia-File-500-APK**.
7. Giải nén file tải về để lấy `Chia-File-500.apk`.

APK này là bản debug, có thể cài trực tiếp lên điện thoại Android để sử dụng thử.

## Cách dùng app

1. Mở app.
2. Bấm **CHỌN THƯ MỤC**.
3. Chọn thư mục cần chia.
4. App sẽ hiển thị số file trực tiếp trong thư mục.
5. Giữ `500` hoặc nhập số file tối đa khác.
6. Bấm **BẮT ĐẦU TÁCH**.
7. Xác nhận.
8. Chờ đến khi app báo hoàn tất.

## Lưu ý

- Nên thử trước với một thư mục test nhỏ.
- Các file sẽ được DI CHUYỂN sang `Tên thư mục - P1`, `P2`... chứ không tạo bản sao.
- Android có thể yêu cầu cho phép cài ứng dụng từ nguồn ngoài khi cài APK tải từ GitHub.
- App dùng trình chọn thư mục chuẩn của Android nên chỉ có quyền với thư mục bạn đã chọn.
