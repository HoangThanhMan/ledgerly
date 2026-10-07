# Tuần 12: Ra mắt, CV và luyện phỏng vấn

| Thời gian | Giai đoạn | Mốc | Ngân sách | Trạng thái |
|---|---|---|---|---|
| 11/01 – 17/01/2027 | 6: Ra mắt | **M6**: `v1.2.0` | 15 giờ | ⬜ Chưa bắt đầu |

## Mục tiêu

Biến code thành **câu chuyện kiểm chứng được**: bài blog, CV có số liệu thật, và khả năng bảo vệ từng dòng code trong phỏng vấn. Cùng một repo được kể theo hai cách, cho vị trí backend và cho vị trí AI Engineer.

## Công việc

| ID | Việc | Giờ | Đầu ra |
|---|---|:-:|---|
| W12-01 | Blog tiếng Việt trên Viblo và tiếng Anh trên dev.to: "Chống double-spend bằng sổ cái kép với Java 25 và PostgreSQL" | 4 | 2 bài |
| W12-02 | GIF demo (tạo bằng `vhs` hoặc quay màn hình) và ảnh dashboard Grafana | 1 | `docs/images/` |
| W12-03 | Rà lại README: mục "Giới hạn và hướng phát triển", liên kết tới `docs/ai-usage.md` | 1.5 | |
| W12-04 | CV: 3–4 bullet theo công thức XYZ với **số đo thật**, bản tiếng Anh và tiếng Việt. **Hai bản:** backend và AI Engineer | 3 | CV |
| W12-08 | Blog thứ hai: "Cho agent chạm vào tiền: xác nhận do server ép và eval chấm trên sổ cái" | 1 | 1 bài |
| W12-05 | Luyện: pitch 2 phút, trình bày 5 phút, trả lời bộ câu hỏi deep-dive (ghi âm rồi nghe lại) | 3 | |
| W12-06 | Tag `v1.2.0`, release notes, đóng milestone, viết retrospective | 1 | `docs/journal/retrospective.md` |
| W12-07 | Dọn backlog: đóng hoặc chuyển issue sang mục "Hướng phát triển" | 0.5 | |

## Mẫu bullet CV

Thay `[...]` bằng số đo thật. **Chỉ ghi số mà bạn giải thích được cách đo.**

> **Ledgerly: Double-entry wallet ledger** (Java 25, Spring Boot 4.1, PostgreSQL, Kafka) · github.com/&lt;user&gt;/ledgerly
> - Guaranteed zero double-spend and exact money conservation across **[N] concurrent transfers**, verified by property-based invariant tests after every load test, using ordered pessimistic locking (`SELECT … FOR UPDATE`).
> - Eliminated duplicate charges under client retries (**[50] concurrent identical requests → 1 transaction**) with a Stripe-style `Idempotency-Key` layer handling in-progress, conflict and crash recovery.
> - Achieved **0 lost events across [5] fault-injection scenarios** (Kafka outage, relay `kill -9`, bank timeouts) via transactional outbox, idempotent consumers and a compensating saga, tested with Testcontainers + Toxiproxy.
> - Sustained **[X] TPS at p99 < [Y] ms** (k6 constant-arrival-rate, [cấu hình máy], methodology in repo). Reconciliation job auto-resolved **[Z]%** of ambiguous bank outcomes.

### Bản cho vị trí AI Engineer

> **Ledgerly Assistant: tool-calling agent over a double-entry ledger** (Python, Claude API, MCP, pgvector; Java 25, Spring Boot) · github.com/&lt;user&gt;/ledgerly
> - Built an agent that reads balances and proposes transfers through an MCP server over a real ledger API. The agent can only *propose*: execution needs a user-held token, enforced server-side, so **[N] prompt-injection tasks × 3 trials produced 0 unauthorized transfers**.
> - Designed an eval suite of **[N] tasks graded on ledger state**, not on the agent's words. Reported pass^3, cost per task and p95 latency, and used it to compare **[3] models** ([X]% vs [Y]% pass^3 at [A]× the cost).
> - Made agent retries safe: tool calls carry an idempotency key derived from the conversation, so **[N] replayed tool calls created 1 transfer intent**.
> - Added hybrid retrieval (pgvector + full-text) over a policy knowledge base, **recall@5 [X]** on a labelled Vietnamese/English set, with an LLM judge calibrated against **[N]** hand labels ([Z]% agreement).

Hai bản dùng chung dòng tiêu đề repo. Bản AI đặt các bullet trên lên trước và giữ một bullet về lõi sổ cái. Bản backend làm ngược lại.

## Bộ câu hỏi deep-dive (tổng hợp)

| Chủ đề | Câu hỏi phải trả lời trôi chảy |
|---|---|
| Tổng quan | Trình bày dự án trong 2 phút. Phần khó nhất là gì? Nếu làm lại, bạn đổi gì? |
| Đồng thời | Vì sao khóa bi quan cho hot account? Tránh deadlock A→B / B→A thế nào? READ COMMITTED khác SERIALIZABLE ra sao? |
| Idempotency | Key lưu ở đâu, TTL bao lâu? Hai request cùng key đến cùng lúc? Cùng key khác body? Crash khi `IN_PROGRESS`? |
| Outbox, Kafka | Vì sao không dual-write? Relay phát trùng thì sao? Thứ tự sự kiện (partition key)? Vì sao Kafka EOS không đủ? |
| Saga | Vì sao saga thay vì 2PC hay TCC? Bank timeout nhưng đã trừ tiền? Đối soát phát hiện chênh lệch thế nào? |
| Virtual threads | Khác platform thread thế nào? Pinning và JEP 491? Vì sao có thể làm hệ thống tệ hơn? ScopedValue khác ThreadLocal ở đâu? |
| Spring | `@Transactional` qua proxy? Self-invocation? Bean scope? Spring Boot 4 thay đổi gì so với Boot 3? |
| Benchmark | p99 đo thế nào, trên máy nào? Coordinated omission? Nút cổ chai ở đâu, sao bạn biết? Tải tăng 10 lần thì gì vỡ trước? |
| Dữ liệu | Có những index nào, vì sao? Vì sao `long` mà không `double`? Vì sao entry không bao giờ bị `UPDATE`? |
| Kiến trúc | Vì sao modular monolith? Khi nào bạn sẽ tách service? ArchUnit chặn được gì? |
| Agent và an toàn | Vì sao xác nhận do server ép? Prompt injection trực tiếp và gián tiếp? Lộ token của agent thì mất gì? Agent gọi lại tool thì sao? |
| Eval | Vì sao chấm trên trạng thái sổ cái? `pass^k` là gì? Làm sao biết giám khảo bằng model chấm đúng? Bộ eval đạt 100% thì làm gì? |
| RAG và chi phí | Vì sao tìm kiếm lai? Recall đo thế nào? Một task tốn bao nhiêu, đổi model rẻ hơn thì mất gì? |
| AI và quyền sở hữu code | Phần nào AI viết? Bạn kiểm chứng bằng cách nào? Giải thích từng dòng hàm `transfer()` |

## Definition of Done

- [ ] Ba bài blog đã đăng (hai bài về sổ cái, một bài về agent), link trong README
- [ ] Hai bản CV đã cập nhật và có người khác review (bạn bè hoặc mentor)
- [ ] Trả lời được 100% bộ câu hỏi, mỗi câu dưới 2 phút
- [ ] Tag `v1.2.0`
- [ ] **Mốc M6 đạt**

## Sau tuần 12

- Dùng dự án cho vòng CV và vòng phỏng vấn sâu. **Vẫn duy trì luyện thuật toán.**
- Mỗi lần phỏng vấn xong, thêm câu hỏi mới vào bảng trên.
- Có thể đóng góp một PR nhỏ cho thư viện đã dùng (Testcontainers, jqwik...) để có thêm tín hiệu làm việc nhóm.
