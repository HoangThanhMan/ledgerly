## Thay đổi gì và vì sao

<!-- 2–3 câu. Liên kết issue: Closes #N -->

## Bằng chứng

- [ ] Test mới hoặc test đã sửa: <!-- tên test -->
- [ ] Bất biến I1–I7 vẫn đúng (nếu chạm vào luồng tiền)
- [ ] Kết quả benchmark (nếu là `perf`): <!-- link perf/results/... -->

## Checklist tự review

- [ ] Tiêu đề PR theo Conventional Commits
- [ ] Dưới khoảng 400 dòng thay đổi (không tính test và tài liệu)
- [ ] Không dùng `double`/`float` cho tiền, không có `@Transactional` ngoài `internal.application`
- [ ] Đã cập nhật README nếu thay đổi ảnh hưởng tới nó
- [ ] Giải thích được từng dòng thay đổi
