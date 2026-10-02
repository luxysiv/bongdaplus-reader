# ⚽ Bóng Đá Plus Reader — App Android đọc báo bóng đá

App đọc tin từ **https://bongdaplus.vn**, có **đăng nhập tài khoản thật**
(member.bongdaplus.vn) và **thông báo tin mới theo chuyên mục theo dõi**.

> Site BongdaPlus **không có API/RSS công khai** nên app dùng kỹ thuật
> đọc HTML (Jsoup) + WebView đăng nhập chính chủ. Tôn trọng bản quyền:
> bài chi tiết luôn ghi nguồn + link gốc.

## Tính năng
- 📰 Trang chủ: Mới nhất, Việt Nam, Ngoại hạng Anh, C1, La Liga, Serie A,
  Bundesliga, Ligue 1, Chuyển nhượng, Nhận định, Hậu trường, Video…
- 🔍 Tìm kiếm, ⭐ lưu tin đọc sau (offline bookmark)
- 🔐 **Đăng nhập**: mở trang login chính thức của BongdaPlus trong app
  (hỗ trợ Email + Google + Apple như trên web). Session lưu bằng cookie,
  dùng để đọc bài Premium.
- 🔔 **Thông báo từ tài khoản**: sau khi đăng nhập, vào ⚙ Cài đặt chọn
  chuyên mục theo dõi → app quét tin ~45 phút/lần (WorkManager), có tin
  mới sẽ đẩy notification, bấm để mở bài.
- 🌙 Tự theo Material You, đọc bài tối ưu mobile.

## Cách mở / build (Android Studio)
1. Mở Android Studio → **Open** → chọn thư mục `BongDaPlus`.
2. Đợi Gradle sync (cần mạng lần đầu).
3. **Run ▶** trên máy thật / emulator (minSdk 24 = Android 7+).
4. Build APK: menu **Build → Build App Bundle(s)/APK(s) → Build APK(s)**,
   file ra ở `app/build/outputs/apk/debug/app-debug.apk`.

Yêu cầu: Android Studio Hedgehog+, JDK 17, Gradle 8.7 (đã cấu hình wrapper).

## Đăng nhập thế nào?
1. Trang chủ bấm **Đăng nhập**.
2. Nhập Email + Mật khẩu tài khoản BongdaPlus (hoặc Google/Apple).
   Nếu chưa có tài khoản: tạo tại `member.bongdaplus.vn/Identity/Account/Register`.
3. Login xong app tự nhận session → hiện nút Thoát, mở được bài Premium,
   bật thông báo cá nhân hoá ở màn ⚙.

## Thông báo thế nào?
- Android 13+ sẽ hỏi quyền Notifications → bấm Cho phép.
- Vào ⚙ → bật "Nhận thông báo" + tick chuyên mục (VD: Việt Nam, MU…).
- WorkManager chạy nền ~45 phút, có tin mới bắn notification kèm link bài.
- Test nhanh: `adb shell` → App inspection → WorkManager → Enqueue `news_poll`,
  hoặc đợi lần quét đầu.

## Kiến trúc
```
MainActivity (NavHost: home/detail/login/saved/settings)
ui/Screens.kt + ViewModels.kt (Jetpack Compose + Material3)
data/BongDaPlusScraper.kt (Jsoup parse bongdaplus.vn)
data/AuthManager.kt (DataStore login state + CookieManager session)
data/BookmarkStore.kt (DataStore bookmarks JSON)
notify/NewsWorker.kt (PeriodicWork + NotificationCompat)
```

## Lưu ý pháp lý
- Tin + ảnh thuộc Tạp chí Bóng Đá. App chỉ đọc/hiển thị kèm nguồn, không
  re-upload. Không dùng cho mục đích thương mại khi chưa xin phép tòa soạn.
