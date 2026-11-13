# ScreenWatcher (Android)

Quét **chữ** (OCR offline, ML Kit) hoặc **hình ảnh mẫu** (template matching) trong **một hoặc nhiều vùng** bạn chọn.
Khi khớp → chạy **macro** (chạm vào vị trí tìm thấy, chạm toạ độ cố định, vuốt, chờ, Back/Home...).

## Tạo APK bằng GitHub Actions (không cần cài Android Studio)

Repo đã có sẵn workflow `.github/workflows/build-apk.yml`: mỗi lần push lên `main` GitHub sẽ tự build `ScreenWatcher-debug.apk`.

### Cách 1: dùng terminal (cần `git` và [GitHub CLI](https://cli.github.com))
Chạy trong thư mục `ScreenWatcher` đã giải nén:

```bash
# 1. Đăng nhập GitHub (làm 1 lần)
gh auth login

# 2. Tạo repo và đẩy mã nguồn lên
git init -b main
git add .
git commit -m "ScreenWatcher: bản đầu tiên"
gh repo create screenwatcher --private --source=. --push

# 3. Theo dõi quá trình build (Ctrl+C để thoát, build chạy khoảng 3-6 phút)
gh run watch

# 4. Tải APK về thư mục ./apk
gh run download --name ScreenWatcher-debug-apk --dir apk
ls apk/ScreenWatcher-debug.apk
```

Chạy lại build mà không cần sửa mã:

```bash
gh workflow run "Build APK"
gh run watch
```

Tạo bản Release có kèm APK (để tải trực tiếp từ trang Releases):

```bash
git tag v1.0
git push origin v1.0
gh release view v1.0 --web
```

Nếu build lỗi, xem log:

```bash
gh run view --log-failed
```

### Cách 2: dùng giao diện web
1. Tạo repo mới trên github.com rồi tải toàn bộ nội dung thư mục này lên (giữ nguyên thư mục `.github`).
2. Vào tab **Actions** → **Build APK** → **Run workflow**.
3. Khi chạy xong, mở lần chạy đó, kéo xuống mục **Artifacts** và tải `ScreenWatcher-debug-apk`.

### Cài APK vào điện thoại
Copy `ScreenWatcher-debug.apk` sang máy, mở file và cho phép "cài từ nguồn không xác định". Hoặc qua USB (đã bật Gỡ lỗi USB):

```bash
adb install -r apk/ScreenWatcher-debug.apk
```

## Build bằng Android Studio
1. Cài Android Studio (Koala trở lên), mở thư mục `ScreenWatcher`, chờ Gradle sync (cần mạng).
2. Cắm điện thoại (Android 8.0+), bật Gỡ lỗi USB, bấm Run.
   Hoặc: `Build > Build APK(s)` rồi cài `app-debug.apk`.

## Cách dùng
1. Mở app, làm lần lượt 3 bước cấp quyền:
   1. Hiển thị trên ứng dụng khác
   2. Bật Dịch vụ trợ năng "ScreenWatcher" (cho phép chạm tự động)
   3. Cấp quyền chụp màn hình
2. **+ Thêm luật**:
   - Kiểu **Chữ**: nhập chữ cần tìm (dùng `|` để thêm lựa chọn, vd `Bỏ qua|Skip`). So khớp không phân biệt hoa/thường và dấu.
   - Kiểu **Hình ảnh**: bấm *Chọn ảnh mẫu trên màn hình*, khoanh đúng hình cần nhận diện.
   - *Chọn vùng*: kéo trên màn hình thật để khoanh vùng cần quét (thêm được nhiều vùng). Nút ✋ cho phép bạn chuyển sang app khác rồi ✏ để vẽ tiếp.
   - *Macro*, ví dụ:
     ```
     tap_match
     wait 500
     tap 540 1800
     ```
   - Bấm **Thử luật** để kiểm tra khớp hay không (chưa chạy macro), rồi **Lưu**.
3. Ở màn hình chính bật **Bật theo dõi**. Dừng bằng công tắc hoặc nút "Dừng hẳn" ở thông báo.
4. **Nút nổi** (hiện sau khi cấp quyền chụp màn hình, tắt/bật bằng công tắc "Hiện nút nổi"): kéo để di chuyển, chạm để mở bảng nhanh gồm công tắc **Theo dõi**, danh sách luật để bật/tắt từng luật, **+ Luật** (thêm luật mới), **Mở app**, **Ẩn nút**. Nút xanh = đang theo dõi, xám = đang tắt. Nút tự ẩn khi bạn đang khoanh vùng/ảnh mẫu.

## Lưu ý
- Android 14+ hỏi lại quyền chụp màn hình mỗi lần khởi động dịch vụ.
- Một số app (ngân hàng, DRM...) chặn chụp màn hình hoặc chặn dịch vụ trợ năng; app đó sẽ hiện màn hình đen / không chạm được.
- Template matching tự thử nhiều tỉ lệ (0.4x–2.5x) nên ảnh trong vùng quét to/nhỏ hơn ảnh mẫu vẫn nhận được (log sẽ ghi tỉ lệ tìm thấy). Nên chọn hình có nhiều chi tiết, không quá nhỏ, và thu hẹp vùng quét để nhanh hơn. Nếu ảnh bị co giãn nhiều, có thể cần hạ ngưỡng khớp xuống ~0.7–0.8.
- Chỉ dùng cho mục đích hợp lệ và tuân thủ điều khoản của ứng dụng/trò chơi bạn tự động hoá.
