# Âm Lượng Riêng (App Volume Mixer)

Ứng dụng Android cho phép **chỉnh âm lượng riêng của từng ứng dụng**. Ví dụ: chia đôi màn hình xem phim YouTube và livestream TikTok → tắt tiếng YouTube để nghe TikTok, hoặc giảm TikTok còn 30% trong khi YouTube vẫn 100%.

## Tính năng
- Danh sách ứng dụng đang phát âm thanh, mỗi app có **thanh trượt 0–100%** và **nút tắt tiếng** riêng.
- **Nút nổi** (bong bóng): chạm để mở bảng chỉnh âm lượng nhỏ ngay trên YouTube/TikTok khi đang chia đôi màn hình/cửa sổ nổi. Kéo để di chuyển, chạm ra ngoài để đóng.
- Ghi nhớ mức âm lượng của từng app; tự áp dụng lại khi app tạo trình phát mới (lướt sang video/live khác).
- Thông báo cố định với nút “Bảng nổi” và “Tắt”.
- Banner AdMob nhỏ, **chỉ ở đáy màn hình chính**, không che nội dung; không có quảng cáo trên nút nổi, bảng nổi hay thông báo. Có form đồng ý quyền riêng tư (UMP/GDPR).
- Giao diện tiếng Việt (mặc định) + tiếng Anh, hỗ trợ chế độ tối.

## Cách hoạt động (vì sao cần Shizuku)
Android không cho ứng dụng thường chỉnh âm lượng của app khác. App dùng **Shizuku** (quyền ADB/shell, không cần root, không cần máy tính trên Android 11+):
1. Gọi `IAudioService.getActivePlaybackConfigurations()` qua Shizuku → nhận danh sách trình phát kèm UID của app sở hữu.
2. Gọi `PlayerProxy.setVolume(v)` cho từng trình phát → đặt **hệ số âm lượng** riêng (cơ chế Android dùng nội bộ để tắt tiếng trình phát). Hệ số này nhân với âm lượng tổng, nên phím âm lượng vẫn chỉnh tổng như bình thường.
3. Service nền quét lại mỗi ~0,8 giây để áp dụng cho trình phát mới.

Mã chính: `engine/HiddenAudio.kt` (gọi API ẩn), `engine/MixerEngine.kt` (vòng áp dụng), `service/MixerService.kt` + `service/OverlayController.kt` (service nền + nút nổi), `ui/MainActivity.kt` (màn hình chính + quảng cáo).

## Build APK bằng GitHub Actions
1. Tạo repo mới trên GitHub (VD `quatrang300-oss/VolumeMixer`) và đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → workflow **Build APK** tự chạy (hoặc bấm *Run workflow*).
3. Tải artifact:
   - `VolumeMixer-debug-apk`: bản thử, **dùng quảng cáo thử nghiệm của Google** (hiện chữ “Test Ad”) – an toàn để bạn bấm thử.
   - `VolumeMixer-release`: APK + AAB dùng **quảng cáo thật** (`ca-app-pub-9448422299959897/1128855548`).

Ký bản release bằng keystore riêng (bắt buộc trước khi đưa lên Google Play): thêm các *Repository secrets* `KEYSTORE_BASE64` (`base64 -w0 my.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Nếu không có, bản release được ký bằng khóa debug (chỉ để cài thử).

Mở bằng Android Studio cũng được (AGP 8.11, Kotlin 2.2, compileSdk/targetSdk 36, minSdk 26 – Android 8.0).

## Thiết lập trên điện thoại (1 lần)
1. Cài **Shizuku** từ Google Play.
2. Bật *Tùy chọn nhà phát triển* → bật *Gỡ lỗi không dây*.
3. Mở Shizuku → *Ghép nối* → nhập mã → nhấn *Bắt đầu*.
4. Mở Âm Lượng Riêng → *Cấp quyền* → *Cho phép mọi lúc*.
5. (Tùy chọn) Bật *Nút nổi chỉnh nhanh* và cấp quyền *Hiển thị trên ứng dụng khác*.

Sau khi khởi động lại máy cần mở Shizuku bấm *Bắt đầu* lại (máy root thì không cần).

## Lưu ý AdMob / Google Play
- **Không bấm vào quảng cáo thật của chính mình** – AdMob có thể khóa tài khoản. Dùng bản debug để thử.
- Thêm file `app-ads.txt` trên website nhà phát triển và khai báo trong AdMob.
- Google Play yêu cầu **chính sách quyền riêng tư** (vì có AdMob) và phần khai báo **Foreground service – special use** (lý do: giữ áp dụng âm lượng riêng cho từng app và hiển thị bảng âm lượng nổi).
- Ứng dụng dùng API ẩn của Android qua Shizuku; đã viết với cơ chế dự phòng, nhưng một số ROM tùy biến có thể chặn – khi đó app sẽ báo “Thiết bị chặn quyền âm thanh”.
- Âm lượng tối đa là 100% âm lượng gốc của app (không khuếch đại vượt mức).
