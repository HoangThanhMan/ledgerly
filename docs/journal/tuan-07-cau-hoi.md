# Câu hỏi phỏng vấn tuần 7: đáp án tham khảo

> Đáp án do AI soạn (xem [ai-usage](../ai-usage.md)). Luyện: đọc câu hỏi, tự nói 2 phút, rồi mới đối chiếu. Số liệu lấy từ [benchmarks](../benchmarks.md), [ADR-0007](../adr/0007-opentelemetry-qua-boot-starter-va-grafana-lgtm.md) và [nhật ký tuần 7](2026-W47.md).

## 1. Coordinated omission là gì? Vì sao `constant-arrival-rate` tránh được?

Là lỗi đo của bộ sinh tải **vòng kín**: mỗi người dùng ảo gửi một request, chờ response, rồi mới gửi request tiếp theo. Khi server đứng 2 giây, người dùng ảo đó cũng đứng 2 giây và **không gửi** những request lẽ ra phải gửi trong 2 giây ấy. Bộ đo ghi lại một request chậm, trong khi ngoài đời có hàng trăm người đã phải chờ. Bộ sinh tải và server "phối hợp" với nhau để bỏ sót đúng những mẫu xấu nhất, nên phân vị cao bị báo thấp hơn thật.

Ví dụ trong README của wrk2: cùng một server, đo ngây thơ thì p99 khoảng 6 ms, đo đúng thì khoảng 1,27 giây.

`constant-arrival-rate` của k6 là **vòng mở**: cứ mỗi 1/300 giây thì bắt đầu một request, bất kể các request trước đã xong chưa. Server chậm thì request dồn lại, k6 lấy thêm người dùng ảo để giữ nhịp, và mọi request bị dồn đều được đo. Tốc độ đến do kịch bản quyết định, không do server quyết định, giống người dùng thật: họ không biết và không quan tâm server đang chậm.

Hai điều phải kiểm tra thì vòng mở mới có nghĩa:

- **`dropped_iterations` phải bằng 0.** Tới giờ gửi mà không còn người dùng ảo nào rảnh thì k6 bỏ lượt đó. Lúc ấy bài đo quay lại đúng lỗi cũ. Trong script đây là một threshold.
- **Thời gian đo tính từ lúc k6 thật sự gửi.** Nếu bộ sinh tải tự nó trễ (thiếu CPU), phần trễ đó không nằm trong số đo. Ở dự án này k6 chạy cùng máy với ứng dụng và không bị giới hạn nhân CPU, nên đây là một giới hạn đã ghi trong báo cáo.

Trong dự án có một ví dụ thật, ngoài ý muốn: lượt đo bị nhiễu ngày 08/10 (xem [benchmarks](../benchmarks.md#lần-đo-hỏng-và-nguyên-nhân)). Server đứng từng đợt vài giây, k6 phải dùng tới 345 người dùng ảo cùng lúc (lượt ngay trước đó: tối đa 10) và vẫn bỏ 512 lượt. Một bộ sinh tải vòng kín với số người dùng ảo cố định sẽ lặng lẽ gửi ít đi đúng lúc đó, và báo một p99 đẹp hơn.

## 2. Vì sao báo cáo p99 mà không báo cáo trung bình?

Vì độ trễ không phân bố đều quanh trung bình. Nó lệch phải: đa số request nhanh, một ít rất chậm. Trung bình trộn hai nhóm đó thành một con số không mô tả nhóm nào.

Số liệu của chính dự án, lượt đo hỏng ngày 08/10 (lượt 2 của `2026-10-08-attempt-1`): trung vị **2,77 ms**, trung bình **21,9 ms**, p99 **746 ms**. Trung bình gấp 8 lần trung vị, tức không request "điển hình" nào mất 21,9 ms. Và nó vẫn nhỏ hơn p99 tới 34 lần, tức nó cũng không nói gì về những request tệ. Một con số, sai theo cả hai hướng.

p99 trả lời đúng câu hỏi vận hành: **cứ 100 request thì 1 request tệ hơn mức này.** Lý do nó quan trọng hơn vẻ ngoài:

- Một người dùng gửi nhiều request. Một màn hình gọi 5 API thì xác suất gặp ít nhất một request chậm hơn p99 là 1 − 0,99⁵ ≈ 5%.
- Request chậm thường là request **đáng giá**: tài khoản nhiều giao dịch, giờ cao điểm.
- Đuôi phân bố là nơi lộ ra khóa, GC, hết connection, swap. Trung vị gần như không đổi khi những thứ đó xảy ra (2,2 ms ở lượt sạch, 2,8 ms ở lượt hỏng), còn p99 đổi hơn 40 lần (17 ms lên 746 ms).

Hai điều cần nói thêm khi được hỏi sâu:

- **Không lấy trung bình của các phân vị.** p99 của ba lượt không phải trung bình ba con p99. Dự án báo cáo **trung vị của ba lượt** và công bố cả ba, kèm dữ liệu từng request để ai cũng tính lại được.
- **p99 cần đủ mẫu.** Mỗi lượt có khoảng 85.500 lần chuyển, nên p99 dựa trên khoảng 850 mẫu chậm nhất. p99,9 chỉ còn 85 mẫu, và dự án không báo cáo nó.
- **p99 cũng che được sự cố.** Lượt 5 của baseline: server đứng 2 giây giữa lúc đo, mà p99 vẫn 17,89 ms, vì 2 giây là 0,7% của 5 phút. Vì vậy báo cáo có thêm request chậm nhất và số lượt k6 phải bỏ. Một con số phân vị không thay được việc nhìn cả phân bố.

## 3. Con số p99 đó đo trên máy nào, với dữ liệu bao nhiêu?

Trả lời thẳng, kèm cả những điều làm con số yếu đi. Người hỏi câu này đang kiểm tra xem mình có biết con số của mình có nghĩa gì không.

**Con số:** ở 300 request mỗi giây, p50 2,17 ms, p95 4,21 ms, p99 **17,14 ms**. Trung vị của ba lượt, mỗi lượt 1 phút warm-up và 5 phút đo, k6 vòng mở.

**Máy:** một laptop, Core i7-11800H (8 nhân, 16 luồng), 7 GB RAM, SSD NVMe. **Mọi thứ chạy trên máy đó:** ứng dụng, PostgreSQL 18, Kafka, bộ Grafana LGTM và cả k6. `ledger-app` bị ghim vào 2 nhân, heap 512 MB, pool 10 connection. Không có mạng: mọi kết nối là loopback.

**Dữ liệu:** database trống lúc bắt đầu, lớn dần tới khoảng 300.000 giao dịch và 3.000 ví sau ba lượt. Ví nguồn và đích ngẫu nhiên trong 1.000 ví, nên gần như không có tranh chấp khóa. 5% request là replay.

**Những điều phải nói thêm, không đợi bị hỏi:**

- Ở mức tải đó hệ thống còn rảnh: CPU 18% của hai nhân, không ai chờ connection. Đây là số đo "lúc bình thường", không phải giới hạn.
- Một lần chuyển tiền là 12 câu SQL. Trên hạ tầng thật, mỗi câu thêm một lượt mạng đi về, nên con số sẽ cao hơn nhiều. 17 ms là của loopback.
- Trong 11 lượt đo cùng ngày, 4 lượt có đợt cả server đứng 0,5 đến 6 giây. **Chưa tìm được nguyên nhân**, và báo cáo viết rõ như vậy.
- SLO trong script (p99 dưới 200 ms) được giữ nguyên, không siết theo con số 17 ms, vì con số đó chưa gặp mạng và dữ liệu lớn.

Mọi thứ trên nằm trong `docs/benchmarks.md` và `env.md` của thư mục kết quả, kèm một dòng cho mỗi request để ai cũng tính lại được.

## 4. Nút cổ chai nằm ở đâu, và làm sao bạn biết?

Câu này có hai nửa, và nửa sau quan trọng hơn. Trả lời bằng **phép đo**, và nói rõ chỗ nào là phép đo, chỗ nào là suy đoán.

**Ở mức baseline (300 request mỗi giây) không có nút cổ chai.** CPU của ứng dụng 18% của hai nhân, pool 10 connection hầu như không ai chờ, p99 chờ khóa dưới 1 ms. Hỏi "nút cổ chai ở đâu" cho một hệ thống còn rảnh là hỏi sai. Phải ép nó.

**Ép tới 2.000 request mỗi giây thì CPU của `ledger-app` hết trước.** Bài tăng tải 600 → 1.000 → 1.500 → 2.000:

- Thông lượng dừng ở khoảng **1.560 lần chuyển mỗi giây**, CPU của ứng dụng 1,81 trên 2 nhân.
- PostgreSQL không có dấu hiệu là giới hạn: thông lượng đã chạm trần mà p99 của một câu SQL vẫn khoảng 1 ms.
- Pool cạn, gần 1.000 luồng xếp hàng. Nhìn dashboard thì dễ kết luận "pool là nút cổ chai". Nhưng câu SQL không chậm đi, trong khi CPU của ứng dụng ở 90%: connection bị giữ lâu vì luồng giữ nó đang chờ **CPU** giữa hai câu SQL. Pool cạn là hệ quả. Tăng pool lúc này nhiều khả năng chỉ dời hàng chờ sang chỗ khác (suy luận, chưa thử).

**Làm sao biết CPU đó đi đâu:** dashboard không trả lời được, phải profile. Java Flight Recorder lấy mẫu 30 giây lúc quá tải: 38,9% mẫu nằm trong code đo đạc (observation cho từng câu SQL 13,6%, tracing 10,8%, Observation API 9,4%), driver PostgreSQL 10,5%, Tomcat 7,9%, và code của chính dự án **1,8%**. Một lần chuyển tiền mở 12 observation SQL.

**Những gì chưa biết, nói luôn:**

- Tắt bớt đo đạc thì trần tăng bao nhiêu: chưa đo được. Phép thử duy nhất đổi bốn thứ cùng lúc nên vô dụng.
- Có những đợt cả server đứng 2 đến 12 giây ngay ở tải thấp. Đã loại năm giả thuyết bằng phép đo (bộ nhớ, checkpoint, dữ liệu lớn, tiến trình khác chèn nhân, thiếu nhân). Nguyên nhân thật thì chưa có. Bước tiếp là để JFR chạy liên tục cho tới khi bắt được một đợt.

**Công thức chung khi được hỏi câu này:** (1) ép tới khi có thứ gì đó chạm trần, (2) xem tài nguyên nào bão hòa **trước**, phân biệt nguyên nhân với hệ quả, (3) profile để biết bên trong tài nguyên đó, (4) đổi **một** thứ rồi đo lại. Dự án làm được ba bước đầu. Bước bốn còn nợ.
