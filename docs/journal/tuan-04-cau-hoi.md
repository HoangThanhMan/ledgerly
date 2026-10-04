# Câu hỏi phỏng vấn tuần 4: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu. Số liệu lấy từ [ADR-0004](../adr/0004-khoa-bi-quan-co-thu-tu.md).

## 1. Lost update là gì? READ COMMITTED có ngăn được không? `FOR UPDATE` giúp thế nào?

**Lost update** xảy ra khi hai transaction cùng làm *đọc → tính → ghi* trên một dòng, và lần ghi sau đè mất lần ghi trước. Ví có 100, T1 và T2 cùng đọc 100, cùng tính 100 − 1 = 99, cùng ghi 99. Hai lần rút mà số dư chỉ giảm một.

**READ COMMITTED không ngăn được.** Nó chỉ đảm bảo mỗi câu lệnh thấy dữ liệu đã commit. Giữa câu `SELECT` và câu `UPDATE` của T1, T2 vẫn commit được. Câu `UPDATE` của T1 có chờ khóa dòng của T2, nhưng sau khi chờ xong nó vẫn ghi giá trị 99 đã tính từ số dư cũ. Trong Ledgerly, `HotWalletDrainIT` trước khi có khóa cho thấy mức độ: 500 luồng rút 1 đồng từ ví có 100 đồng, **cả 500** lần đều thành công. Ràng buộc `balance >= 0` cũng không bắt được, vì giá trị nào được ghi cũng không âm.

**`SELECT ... FOR UPDATE`** lấy khóa dòng ngay lúc **đọc** và giữ tới hết transaction. T2 phải chờ ở câu `SELECT`. Khi T1 commit, T2 (ở READ COMMITTED) đọc lại phiên bản mới nhất của dòng, tức 99, rồi mới tính. *Đọc → tính → ghi* trở thành một đoạn loại trừ lẫn nhau trên từng account. Ledgerly dùng `FOR NO KEY UPDATE`: cũng loại trừ các posting khác, nhưng không chặn `INSERT` có khóa ngoại tới account.

Cách khác: `UPDATE ... SET balance = balance - 1 WHERE balance >= 1` (một câu, nguyên tử), hoặc khóa lạc quan bằng cột `version`.

## 2. Vì sao khóa theo thứ tự id thì không deadlock? Chứng minh ngắn gọn.

Deadlock là một **vòng chờ**: T1 giữ khóa X và chờ Y, T2 giữ Y và chờ X.

Giả sử mọi transaction lấy khóa theo `id` tăng dần, và có một vòng chờ T1 → T2 → … → Tn → T1. "Ti chờ Ti+1" nghĩa là Ti đang chờ một khóa mà Ti+1 giữ. Vì lấy theo thứ tự tăng, khóa mà Ti đang chờ **lớn hơn** mọi khóa Ti đang giữ. Gọi m(T) là khóa lớn nhất T đang giữ: khóa Ti chờ lớn hơn m(Ti), và Ti+1 đang giữ nó, nên m(Ti+1) > m(Ti). Đi hết vòng thì m(T1) < m(T2) < … < m(Tn) < m(T1), vô lý. Vậy không có vòng chờ.

Điều kiện là thứ tự phải **toàn cục** và mọi đường ghi đều theo. Số liệu trong dự án: 2.000 lần chuyển ngược chiều giữa hai ví cho 0 deadlock khi khóa bằng `ORDER BY id`, và 129–143 deadlock khi khóa nguồn trước, đích sau.

Điểm dễ sai: **bỏ `ORDER BY` thì một câu `SELECT ... WHERE id = ANY(...) FOR UPDATE` vẫn deadlock** (72–74 lần trong thí nghiệm). Không có `ORDER BY`, dòng được khóa theo thứ tự quét, tức theo vị trí vật lý, và vị trí đó đổi sau mỗi `UPDATE`.

## 3. Khi nào khóa lạc quan tốt hơn khóa bi quan? Vì sao hot account là trường hợp xấu cho khóa lạc quan?

**Khóa lạc quan** không giữ khóa trong lúc tính: đọc dòng kèm `version`, rồi `UPDATE ... WHERE id = ? AND version = ?`. 0 dòng bị ảnh hưởng nghĩa là có người ghi trước, phải đọc lại và làm lại.

Tốt hơn khi:

- **Xung đột hiếm:** nhiều dòng khác nhau, mỗi dòng ít người ghi. Phần lớn transaction thắng ngay lần đầu, không tốn khóa.
- **Thời gian "suy nghĩ" dài hoặc đi qua mạng:** người dùng mở form, sửa, rồi lưu. Không thể giữ khóa database qua nhiều request.
- **Đọc nhiều, ghi ít.**

**Hot account** là trường hợp ngược lại: một dòng mà rất nhiều transaction cùng ghi (`system:funding`, ví của một merchant lớn). N transaction cùng đọc `version = 7`, chỉ **một** `UPDATE` thành công. N − 1 còn lại đã tốn công đọc, tính, ghi entry, rồi phải rollback và làm lại. Vòng sau lại chỉ một thắng. Tổng công việc là cỡ N², độ trễ đuôi tăng mạnh, và có thể có transaction thua mãi nếu không có backoff. Khóa bi quan trên cùng dòng thì N transaction **xếp hàng** và mỗi cái làm đúng một lần.

Ledgerly chọn khóa bi quan (ADR-0004) vì hot account là một phần của thiết kế. Phần so sánh này là lập luận: tuần 11 mới đo cả hai chiến lược.

## 4. Ở SERIALIZABLE, PostgreSQL làm gì khi phát hiện xung đột? Ứng dụng phải xử lý ra sao?

PostgreSQL cài SERIALIZABLE bằng **Serializable Snapshot Isolation**: mỗi transaction chạy trên snapshot như REPEATABLE READ, và database theo dõi thêm các phụ thuộc đọc–ghi giữa các transaction đồng thời (bằng predicate lock, loại khóa **không chặn** ai). Khi các phụ thuộc tạo thành một cấu trúc có thể dẫn tới kết quả không tương đương với bất kỳ thứ tự tuần tự nào, PostgreSQL **hủy một transaction** với lỗi:

```text
ERROR: could not serialize access due to read/write dependencies among transactions
SQLSTATE 40001 (serialization_failure)
```

Lỗi có thể đến ở bất kỳ câu lệnh nào, kể cả lúc `COMMIT`.

Ứng dụng phải:

1. **Retry cả transaction** từ đầu, không chỉ câu lệnh cuối, vì mọi giá trị đã đọc đều có thể cũ.
2. **Giới hạn số lần retry** và có backoff kèm jitter.
3. **Không để hiệu ứng phụ ngoài database** (gọi HTTP, gửi Kafka) trong transaction, vì chúng sẽ lặp lại.
4. **Không tin dữ liệu đã đọc cho tới khi commit thành công.**
5. Dùng SERIALIZABLE cho **mọi** transaction liên quan, nếu không thì đảm bảo không còn.

Vì sao Ledgerly không dùng cho posting: với hot account, hầu hết transaction đồng thời phụ thuộc lẫn nhau, nên tỉ lệ `40001` cao và mỗi lần là một lần làm lại. Khóa dòng có thứ tự cho cùng đảm bảo trên dòng account mà không cần vòng retry.

## 5. Virtual thread có giúp transaction nhanh hơn không? Vì sao?

**Không.** Một transaction mất bao lâu là do database: thời gian chạy câu lệnh, chờ khóa, ghi WAL lúc commit. Virtual thread không rút ngắn được phần nào trong đó.

Virtual thread làm cho việc **chờ** trở nên rẻ: một luồng đang chờ I/O không chiếm một luồng hệ điều hành và khoảng 1 MB stack. Nhờ vậy server nhận được rất nhiều request đồng thời với code viết kiểu tuần tự. Nhưng đó là **sức chứa**, không phải tốc độ.

Nút cổ chai thật là **connection pool**. `ConcurrentTransferIT` chạy 200 virtual threads trên pool HikariCP 10 connection: lúc nào cũng chỉ tối đa 10 transaction thật sự chạy trong database, 190 luồng còn lại đứng chờ `getConnection()`. Tăng lên 2.000 virtual threads không làm 10.000 lần chuyển xong sớm hơn. Thí nghiệm deadlock cho thấy mặt trái: khi các connection bị giữ bởi transaction đang chờ khóa, 1.600–1.900 trên 2.000 lần chuyển thất bại vì **không mở được transaction**.

Vì vậy với virtual thread cần chủ động chặn tải ở tầng ứng dụng (`@ConcurrencyLimit`, tuần 10) và giữ transaction ngắn: không gọi mạng trong lúc giữ connection. Một điểm cũ không còn đúng: từ JDK 24 (JEP 491) `synchronized` không còn ghim virtual thread vào luồng mang.
