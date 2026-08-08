# YT to MIDI — bản Android

Ứng dụng Android (Kotlin + Jetpack Compose) port lại đúng thuật toán trong
`extract_midi.py` (phát hiện phím sáng màu xanh lá/xanh dương kiểu Synthesia,
xuất file `.mid` 2 track tay trái/phải) — không cần OpenCV hay Python trên máy,
chạy hoàn toàn native trên điện thoại.

## Cách lấy file APK (không cần cài Android Studio)

1. Copy toàn bộ các thư mục/tệp trong gói này (`app/`, `build.gradle.kts`,
   `settings.gradle.kts`, `gradle.properties`, `.github/`) vào **gốc repo**
   GitHub của bạn (`youtube-to-midi-mobile`), giữ nguyên các file Python cũ
   cũng không sao — không đụng tới nhau.
2. Commit & push lên nhánh `main`.
3. Vào tab **Actions** trên GitHub → workflow **"Build APK"** sẽ tự chạy
   (mất khoảng 3–5 phút).
4. Khi chạy xong, mở lần chạy đó → mục **Artifacts** → tải
   `yt-to-midi-debug-apk.zip` → giải nén ra được `app-debug.apk`.
5. Chuyển file `.apk` đó vào điện thoại (qua Drive, USB, Zalo gửi cho chính
   mình...), mở lên cài (bật "Cài từ nguồn không xác định" nếu máy hỏi).

Đây là APK bản debug (chưa ký release) — cài trực tiếp lên máy vẫn chạy bình
thường, chỉ không đăng được lên Play Store.

## Nếu bạn có Android Studio trên máy tính

Mở thư mục project này bằng Android Studio (Hedgehog trở lên) → để nó tự tải
Gradle/SDK (cần mạng) → bấm Run, hoặc **Build > Build Bundle(s)/APK(s) >
Build APK(s)**.

## Cách dùng app

1. Mở app → **"Chọn video"** → chọn video hướng dẫn piano (Synthesia-style)
   đã tải sẵn trong máy (app không tự tải video từ YouTube).
2. Chỉnh các thông số nếu cần:
   - **Vị trí dòng phím (Y)**: dòng ngang nơi phím sáng lên trong khung hình.
   - **Bỏ qua đầu video**: số giây intro cần bỏ qua.
   - **Tốc độ lấy mẫu**: số khung hình phân tích mỗi giây — cao hơn thì chính
     xác hơn nhưng xử lý lâu hơn (khác với video gốc, vì Android phải "tua"
     tới từng khung thay vì đọc tuần tự như OpenCV trên máy tính, nên video
     dài + tốc độ lấy mẫu cao sẽ mất vài phút xử lý).
   - **Tempo xuất MIDI**: mặc định 120 BPM giống bản gốc.
3. Bấm **"Bắt đầu trích xuất"**, chờ thanh tiến trình chạy xong.
4. Bấm **"Chia sẻ / Lưu file .mid"** để lưu vào Drive, gửi qua app khác, v.v.

## Giới hạn (kế thừa từ bản gốc + đặc thù di động)

- Calibration mặc định vẫn tính theo video 1276x720 như script gốc; app tự co
  giãn theo độ phân giải video bạn chọn, nhưng nếu bàn phím trong video nằm
  lệch nhiều, bạn vẫn cần chỉnh tay "Vị trí dòng phím (Y)".
- Không xuất velocity (mặc định 100) và không phát hiện tempo — giống hệt bản
  Python gốc.
- Xử lý trên điện thoại chậm hơn máy tính vì cách Android truy xuất từng khung
  hình video; nên thử với video ngắn trước.
