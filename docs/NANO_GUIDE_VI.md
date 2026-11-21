# Hướng dẫn sử dụng GNU Nano trên Termux

Đây là tài liệu tham khảo tiếng Việt cho GNU Nano trên Termux. Termux đóng gói GNU Nano upstream; script gói hiện lấy tarball Nano 9.3 từ nano-editor.org, khai báo giấy phép GPL-3.0 và cấu hình `nanorc` để nạp các tệp tô màu cú pháp đi kèm.

## Cài đặt và mở tệp

```bash
pkg update
pkg install nano
nano ten_tep.py
nano +25 ten_tep.py
```

`nano +25 ten_tep.py` mở tệp tại dòng 25.

## Phím tắt thường dùng trong Nano 9.x

| Phím | Tác dụng |
|---|---|
| `Ctrl+S` | Lưu buffer hiện tại |
| `Ctrl+O` | Ghi tệp / chọn tên tệp để lưu |
| `Ctrl+R` | Chèn nội dung từ một tệp khác |
| `Ctrl+X` | Thoát Nano |
| `Ctrl+F` / `Ctrl+B` | Tìm tiến / tìm lùi trong các bản Nano 8 trở lên |
| `Alt+R` | Tìm và thay thế |
| `Alt+F` / `Alt+B` | Tìm lần xuất hiện tiếp theo / trước đó |
| `Ctrl+K` | Cắt dòng hoặc vùng đã đánh dấu |
| `Alt+6` | Sao chép dòng hoặc vùng đã đánh dấu |
| `Ctrl+U` | Dán cutbuffer |
| `Alt+U` / `Alt+E` | Undo / Redo |
| `Alt+G` | Đi tới dòng được chỉ định |
| `Alt+3` | Comment / Uncomment dòng hoặc vùng |
| `Ctrl+C` | Hiện vị trí con trỏ |
| `Ctrl+G` | Mở trợ giúp trong Nano |
| `Alt+N` | Bật / tắt số dòng |

`Ctrl` là phím Control; `Alt` là phím Meta trên hầu hết bàn phím. Nếu tổ hợp Alt không gửi được trong terminal, Nano cho phép dùng Escape làm phím Meta theo cấu hình terminal.

## Cấu hình cơ bản

Có thể cấu hình Nano bằng `~/.nanorc`. Các tùy chọn nên cân nhắc:

```nanorc
set linenumbers
set tabsize 4
set tabstospaces
set autoindent
set softwrap
```

Không cần chép lại cấu hình hệ thống Termux nếu gói Nano đã nạp cấu hình mặc định của nó. Hãy thêm các tùy chọn cá nhân vào `~/.nanorc`.

## Trình chỉnh sửa của Hs

Editor Android của Hs là trình soạn thảo riêng, không phải bản giao diện Nano và không nhúng mã nguồn GNU Nano. Menu **Nano** trong thanh công cụ cung cấp các thao tác tương tự: tìm trong tệp, tìm tiếp/lùi, đi tới dòng, nhân đôi dòng/vùng chọn, thụt lề, comment/uncomment, thống kê dòng/từ/ký tự và mở tài liệu tham khảo.

## Tài liệu chính thức

- Trang mã nguồn GNU Nano: https://www.nano-editor.org/git.php
- Sổ tay GNU Nano: https://www.nano-editor.org/dist/latest/nano.html
- Bảng phím tắt: https://www.nano-editor.org/dist/latest/cheatsheet.html
- Script đóng gói Nano của Termux: https://github.com/termux/termux-packages/blob/master/packages/nano/build.sh

GNU Nano được phát hành theo GPL-3.0. Tài liệu này chỉ mô tả cách dùng và phím tắt; phần code editor của Hs được triển khai riêng.
