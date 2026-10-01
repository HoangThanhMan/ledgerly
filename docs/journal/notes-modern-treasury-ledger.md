# Ghi chú: Modern Treasury: How to Scale a Ledger (phần I–II)

- **Nguồn:** https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-i và https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-ii
- **Đọc để hiểu:** Mô hình Accounts / Transactions / Entries
- **Liên quan trong Ledgerly:** [01-kien-truc §5](../01-kien-truc.md#5-mô-hình-dữ-liệu), [ADR-0003](../adr/0003-bieu-dien-tien-te.md)
- **Ngày đọc:** 2026-10-01 (AI đọc và ghi chú, xem [ai-usage](../ai-usage.md))

## Ý chính

- **Phần I:** sổ cái gồm ba khái niệm. *Account* là một "bể giá trị" riêng. *Transaction* là một sự kiện tiền tệ nguyên tử. *Entry* là từng dòng debit/credit tạo nên transaction. Lời khuyên chính: **áp đặt double-entry và tính bất biến ngay tại nguồn** phát sinh sự kiện tài chính, thay vì đi đối soát sau giữa các hệ thống rời rạc. Mọi thay đổi được ghi lại để luôn truy vấn được trạng thái quá khứ.
- **Phần II:** account mang tiền tệ và một `normal_balance`: debit-normal (tài sản, chi phí) hoặc credit-normal (nợ phải trả, vốn, doanh thu). Entry có `amount` và `direction`, phần lõi bất biến.
- Account lưu sẵn bốn tổng: `posted_debits`, `posted_credits`, `pending_debits`, `pending_credits`. Từ đó suy ra ba loại số dư: **posted** (đã quyết toán), **pending** (gồm cả khoản đang chờ), **available** (được phép chi). Account debit-normal có số dư `debits − credits`, credit-normal thì ngược lại.
- Chỉ entry **pending** mới được thay thế, bằng cách đánh dấu `discarded_at` cho entry cũ. Entry đã posted là vĩnh viễn.

## Áp dụng vào Ledgerly

- Cùng mô hình ba bảng `accounts` / `ledger_transactions` / `entries` và cùng nguyên tắc entry bất biến (P2, trigger chặn `UPDATE`/`DELETE`).
- **Khác về dấu:** Modern Treasury dùng `amount` + `direction` + `normal_balance`. Ledgerly chọn một cột `amount` có dấu nhìn từ phía account (ADR-0003), để I1 và I3 là một câu `SUM` và không phải tra loại account khi tính số dư.
- Ledgerly chỉ có số dư posted. Khái niệm pending/available tương ứng với tính năng *hold* (mục *Could* số 11). Nếu làm, có thể theo cách của họ: thêm cột pending thay vì sửa entry.

## Tự kiểm tra

- **Vì sao entry bất biến? Sửa sai thế nào?** Để lịch sử là bằng chứng kiểm toán và số dư luôn tái tạo được từ entries. Sai thì ghi một giao dịch đảo (`REVERSAL`), không sửa dòng cũ.
- **Họ lưu dấu thế nào?** `amount` dương kèm `direction`, ý nghĩa tăng hay giảm phụ thuộc `normal_balance` của account.
- **Số dư tính hay lưu?** Họ lưu sẵn các tổng debit/credit trên account để đọc nhanh, và giữ entries làm nguồn sự thật. Ledgerly cũng lưu `balance` (đọc nhanh, khóa được) và kiểm tra I3 để phát hiện lệch.

## Còn chưa rõ

- Làm sao họ cập nhật bốn tổng trên account khi có nhiều ghi đồng thời mà không nghẽn ở một dòng (bài toán hot account, tuần 11)? Bài không nói chi tiết.
