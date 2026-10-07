# Đưa hướng AI Engineer vào Ledgerly mà không làm yếu hướng backend

> Nghiên cứu ngày 07/10/2026, do AI thực hiện theo yêu cầu của tác giả (xem [ai-usage](../ai-usage.md)). Mỗi nhận định về thị trường hoặc công nghệ có nguồn ở [cuối file](#nguồn). Nhiều nguồn về tuyển dụng là bài tổng hợp của bên thứ ba, không phải số liệu khảo sát: phần [Giới hạn của nghiên cứu](#giới-hạn-của-nghiên-cứu) nói rõ chỗ nào chắc, chỗ nào không.

## Kết luận

Thêm vào Ledgerly **một trợ lý ví chạy bằng tool calling**, tách thành ba tuần (A1–A3) ngay sau MVP `v1.0.0`:

1. **Tuần A1, phần Java:** danh tính tối thiểu, token theo scope, và **lệnh chuyển tiền chờ xác nhận** (transfer intent). Agent chỉ được *đề xuất* chuyển tiền. Người dùng xác nhận bằng một token mà agent không bao giờ có. Việc này do **server ép**, không do prompt.
2. **Tuần A2, phần Python:** MCP server, vòng lặp agent bằng SDK của Claude, và **bộ eval** chấm bằng chính trạng thái sổ cái.
3. **Tuần A3:** RAG trên kho tri thức (pgvector, tìm kiếm lai), bộ thử an toàn theo OWASP, và bảng so sánh ba model về độ đúng, chi phí, độ trễ.

Cách kể cho cả hai vai trò là **một ý**: *hệ thống tiền có bất biến được kiểm chứng bằng máy, và một agent bị ràng buộc bởi chính các bất biến đó*. Phần backend không bị pha loãng, vì tuần A1 là công việc backend thuần (xác thực, phân quyền, máy trạng thái, đồng thời), và nó cũng vá hai giới hạn đã ghi trong ADR-0005 (key idempotency toàn cục, chưa có định danh người gọi).

Ba việc cần tác giả quyết trước khi làm, vì chúng thay đổi ràng buộc gốc của dự án:

| Quyết định | Mặc định trong kế hoạch | Vì sao là việc của tác giả |
|---|---|---|
| Ngân sách gọi model | Ước lượng 60–100 USD cho cả ba tuần, dùng API của Claude | Charter đang ghi "chi phí: 0 đồng". Mỗi lượt chạy eval tốn tiền thật |
| Thứ tự | A1–A3 chen **sau tuần 8**, tuần 9–12 lùi ba tuần | Lùi saga, chaos và đối soát. Phương án khác là để AI sau tuần 11 |
| Phạm vi xác thực | Token tự quản theo scope, không OAuth2/OIDC | "Xác thực đầy đủ" đang là non-goal. Kế hoạch chỉ nới một phần |

## Nhà tuyển dụng AI Engineer đang hỏi gì

**Mẫu số chung** của các bài tổng hợp tin tuyển 2026 là: Python, gọi API của LLM, thiết kế tool calling và agent, RAG với vector database, **đánh giá (eval)**, quan sát và tối ưu chi phí, phòng chống prompt injection, và gần đây là MCP ([Great Learning](https://www.mygreatlearning.com/blog/the-complete-ai-agent-engineer-skills-stack-you-need-in-2026/), [AY Automate](https://www.ayautomate.com/blog/ai-engineer-skills-2026), [Digital Applied](https://www.digitalapplied.com/blog/ai-developer-hiring-skills-that-matter-2026)). Một bài mô tả eval là kỹ năng "hiếm nhất và được hỏi nhiều nhất", với tin tuyển yêu cầu "eval pipeline, golden dataset, LLM-as-judge" ([Digital Applied](https://www.digitalapplied.com/blog/ai-developer-hiring-skills-that-matter-2026)).

**Ở Việt Nam**, trang việc làm AI Engineer của ITviec ngày 07/10/2026 hiển thị 20 tin. Đếm theo nhãn kỹ năng: Python 14 tin, LLM 9, Machine Learning 8, NLP 6, Computer Vision 4, Agentic AI 2, RAG 2, MLOps 2 ([ITviec](https://itviec.com/it-jobs/ai-engineer)). Hai điều đáng chú ý:

- **Ngân hàng đang tuyển AI Engineer.** Trong 20 tin có MB Bank (ba tin, gồm cả trainee và "all level"), OCB và TNEX. Đây là cùng nhóm nhà tuyển dụng mà Ledgerly nhắm tới cho hướng backend. Một tin của MB ghi nhãn kỹ năng "Python, Microservices" cạnh "AI".
- **Tin cho fresher còn hỏi ML cổ điển.** Tin trainee của MB ghi Machine Learning, Computer Vision, NLP, C++. Các mô tả vị trí fresher khác nhắc PyTorch, TensorFlow, scikit-learn bên cạnh LLM, RAG, LangChain và vector database ([CareerLink](https://www.careerlink.vn/cam-nang-viec-lam/tu-van-nghe-nghiep/ai-engineer-fresher-la-gi), [Indeed](https://vn.indeed.com/q-ai-agent-vi%E1%BB%87c-l%C3%A0m.html)).

Báo cáo gốc của dự án đã ghi nhận xu hướng này từ đầu: theo ITviec, 47,6% công ty chuyển nhu cầu sang kỹ năng AI ([báo cáo chọn đề tài](Dự%20án%20Java%20cho%20CV%20fresher.md)).

## Ledgerly có gì và thiếu gì

| Nhà tuyển dụng hỏi | Ledgerly hiện có | Sau tuần A1–A3 |
|---|---|---|
| Python | Không (tác giả có nền Python từ dự án khác) | Service `assistant` bằng Python, có lint, type check, test, CI |
| Gọi API LLM, tool calling | Không | Vòng lặp agent, tool có schema chặt, streaming |
| MCP | Không | MCP server theo spec `2026-07-28` |
| RAG, vector database | Không | Tìm kiếm lai trên pgvector, có số đo recall |
| **Eval** | Không cho AI. Có văn hóa "bất biến kiểm bằng máy" | Bộ eval có grader bằng mã, `pass^k`, bảng so sánh model |
| Quan sát, chi phí | OTel + Grafana (tuần 7) | Span cho từng lượt model và từng tool, chi phí mỗi task |
| An toàn agent | Không | Xác nhận do server ép, bộ thử prompt injection theo OWASP |
| Thiết kế hệ thống, độ tin cậy | **Mạnh:** khóa có thứ tự, idempotency, outbox | Giữ nguyên, và là nền cho agent |
| Huấn luyện mô hình, CV, NLP cổ điển | Không | **Vẫn không** |

Dòng cuối là giới hạn thật: dự án này chứng minh năng lực **xây ứng dụng trên LLM**, không chứng minh năng lực huấn luyện mô hình. Tin fresher hỏi PyTorch hay Computer Vision cần bằng chứng khác (môn học, nghiên cứu, Kaggle). Kế hoạch có một mục *Could* nhỏ chạm tới phần này (tinh chỉnh model embedding), nhưng không nên trông vào nó.

## Vì sao là trợ lý ví, không phải thứ khác

| Phương án | Điểm mạnh | Điểm yếu | Kết luận |
|---|---|---|---|
| Chatbot hỏi đáp tài liệu dự án (RAG thuần) | Dễ làm, có từ khóa RAG | Không dính nghiệp vụ. Ai cũng có một cái | Loại |
| Mô hình phát hiện gian lận | Đúng ngành fintech, có ML cổ điển | Dữ liệu là giả, nên số đo không nói lên gì | Loại |
| LLM phân loại dòng lệch khi đối soát | Dính nghiệp vụ thật | Phụ thuộc tuần 11. Một lần gọi model, ít chiều sâu | Để làm mục *Could* ở tuần 11 |
| **Trợ lý ví: agent gọi tool trên API thật, có RAG cho câu hỏi về chính sách** | Phủ gần hết danh sách kỹ năng. Dùng lại idempotency, sự kiện, bất biến. Có rủi ro thật (tiền) nên phần an toàn có ý nghĩa | Phải thêm xác thực. Tốn tiền gọi model | **Chọn** |

Lý do quyết định là **phần an toàn có thật**. Một agent chuyển được tiền là ví dụ sách giáo khoa của ba rủi ro trong OWASP Top 10 for Agentic Applications 2026: dùng sai tool (ASI02), lạm dụng danh tính và quyền (ASI03), và lợi dụng lòng tin của người duyệt (ASI09). Bản đó khuyến nghị xác nhận rõ ràng của con người cho hành động tác động lớn và nhật ký kiểm toán không sửa được ([OWASP](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/), tóm tắt các mục ở [Giskard](https://www.giskard.ai/glossary/owasp-asi09-human-agent-trust-exploitation-i2o37)). Ledgerly đã có sổ cái chỉ thêm không sửa và sự kiện qua outbox, nên phần còn thiếu chỉ là lớp xác nhận.

Điểm riêng của thiết kế: **xác nhận nằm ở server, không nằm ở prompt.** Agent giữ token chỉ có scope `transfers:propose`. Nó tạo được một *lệnh chờ xác nhận*, không tạo được giao dịch. Dù model bị lừa hoàn toàn, tiền vẫn không đi nếu người dùng không xác nhận bằng token của họ. Điều đó phát biểu được thành bất biến và kiểm được bằng SQL, giống I1–I7.

## Các lựa chọn công nghệ

### Python cho lớp AI, Java cho nền

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| Spring AI trong `ledger-app` | Một ngôn ngữ, một lần deploy. Spring AI 2.0 chạy trên Boot 4 và có starter cho MCP server ([Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)) | Tin AI Engineer hỏi Python ở 14 trên 20 tin. Lớp AI dính vào lõi tiền. Trang tài liệu của SDK Java cho MCP trỏ tới spec `2025-11-25` và không nhắc bản `2026-07-28` ([MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest), xem ngày 07/10/2026), và Java không nằm trong bốn SDK Tier 1 |
| **Service Python riêng gọi REST API** | Đúng ngôn ngữ thị trường hỏi. Lõi tiền không biết gì về LLM. SDK Python của MCP là Tier 1 và hỗ trợ spec mới nhất ([MCP](https://blog.modelcontextprotocol.io/posts/2026-07-28/)) | Thêm một ngôn ngữ và một pipeline CI |

Chọn Python. Ranh giới qua HTTP cũng là điều kiện để thử an toàn cho đúng: agent chỉ có những quyền mà API cấp.

### MCP

Spec hiện hành là `2026-07-28`: lõi không trạng thái, bỏ bước bắt tay và `Mcp-Session-Id`, thêm Multi Round-Trip Requests cho trường hợp tool cần hỏi thêm giữa chừng. Bốn SDK Tier 1 hỗ trợ bản này là TypeScript, Python, Go và C# ([MCP](https://blog.modelcontextprotocol.io/posts/2026-07-28/)). Bản không trạng thái hợp với cách Ledgerly đã làm mọi thứ khác: mỗi request tự mang đủ ngữ cảnh.

### Không dùng framework agent ở vòng đầu

LangGraph, PydanticAI và các SDK agent của nhà cung cấp là các lựa chọn phổ biến năm 2026 ([Uvik](https://uvik.net/blog/python-ai-agent-frameworks/)). Tin tuyển ở Việt Nam hay nhắc LangChain và LlamaIndex. Kế hoạch vẫn viết vòng lặp bằng SDK của Claude, vì hai lý do: một agent với năm tool không cần đồ thị trạng thái, và tự viết vòng lặp thì trả lời được câu hỏi phỏng vấn "bên dưới framework là gì". ADR-0012 sẽ ghi phép so sánh. Nếu tác giả cần từ khóa LangGraph trên CV, cách rẻ nhất là viết lại vòng lặp bằng LangGraph như một mục *Could* và chạy **cùng bộ eval** để so.

### Eval

Kế hoạch theo hướng dẫn của Anthropic ([Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)):

- Bắt đầu với 20–50 task đơn giản, không chờ có hàng trăm.
- **Chấm kết quả, không chấm đường đi.** Kết quả ở đây là trạng thái của sổ cái sau lượt chạy: có lệnh chờ đúng số tiền không, có giao dịch nào không được phép không.
- Ưu tiên grader bằng mã. Chỉ dùng model làm giám khảo cho câu trả lời tự do (RAG), và phải hiệu chuẩn với nhãn của người.
- Agent không tất định, nên mỗi task chạy nhiều lượt và báo `pass^k` (cả k lượt đều đạt). Ví dụ của bài: 75% mỗi lượt thì `pass^3` chỉ còn khoảng 42%.
- Tách bộ **hồi quy** (phải gần 100%) khỏi bộ **năng lực** (bắt đầu thấp, để leo).
- Mỗi lượt chạy bắt đầu từ môi trường sạch, và phải đọc transcript.

Ledgerly có lợi thế hiếm: "kết quả" kiểm được bằng SQL trên một sổ cái có bất biến.

### RAG

- **Lưu trữ:** pgvector đã có image cho PostgreSQL 18 (`pgvector/pgvector:pg18`, bản 0.8.2), nên không cần thêm một vector database ([pgEdge](https://docs.pgedge.com/pgvector/v0-8-2/additional-installation-methods/)). Tìm kiếm lai kết hợp vector với full-text của PostgreSQL trong một truy vấn.
- **Embedding:** model mở chạy được trên CPU, ví dụ `multilingual-e5-small` (384 chiều, hơn 100 ngôn ngữ) hoặc BGE-M3 nếu máy chịu được ([Google Cloud](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/maas/e5/multilingual-e5-small), [BGE-M3](https://huggingface.co/BAAI/bge-m3)). Lý do: chi phí bằng 0, và câu hỏi sẽ có cả tiếng Việt. Model nào chạy vừa máy 7 GB RAM là việc phải đo ở tuần A3.
- **Nội dung:** kho tri thức về chính sách của ví (hạn mức, biểu phí giả định, giải thích từng mã lỗi `/problems/*`). Đây là thứ agent không lấy được từ tool đọc số liệu, nên RAG có lý do tồn tại.

### Quan sát

Quy ước ngữ nghĩa GenAI của OpenTelemetry (`gen_ai.*`) **vẫn ở trạng thái Development** tính tới tháng 8/2026, chưa có thuộc tính nào Stable ([Greptime](https://www.greptime.com/blogs/2026-05-09-opentelemetry-genai-semantic-conventions), [dev.to](https://dev.to/azena-ai/opentelemetrys-genai-semantic-conventions-are-not-stable-yet-heres-what-actually-shipped-in-2026-3mke)). Kế hoạch vẫn dùng chúng, vì tuần 7 đã dựng OTel và Grafana, và ghi rõ trong ADR rằng tên thuộc tính có thể đổi.

## Chi phí

Giá API của Claude tại ngày 25/09/2026, tính theo một triệu token vào / ra: Opus 5.5 là 4 / 20 USD, Sonnet 5.5 là 2 / 10 USD, Haiku 4.5 là 1 / 5 USD (bảng model trong bộ tài liệu Claude API mà phiên làm việc này dùng).

Ước lượng dưới đây là **của AI, chưa đo**. Giả định một task tốn 4 lượt gọi model, tổng 14.000 token vào và 1.000 token ra, không tính prompt caching:

| Model | Một task | 100 task × 3 lượt |
|---|:-:|:-:|
| Opus 5.5 | khoảng 0,08 USD | khoảng 23 USD |
| Sonnet 5.5 | khoảng 0,04 USD | khoảng 11 USD |
| Haiku 4.5 | khoảng 0,02 USD | khoảng 6 USD |

Một lượt so sánh đủ ba model khoảng 40 USD. Cộng các lượt chạy thử khi phát triển, tổng ba tuần ước 60–100 USD. Tuần A2 có một task riêng để **đo chi phí thật của một lượt chạy** trước khi chạy bộ đầy đủ, và eval thật không chạy tự động trên mỗi PR.

## Thứ tự trong lộ trình

| Phương án | Ưu điểm | Nhược điểm |
|---|---|---|
| **Sau tuần 8** (chọn) | API vừa ổn định và có OpenAPI. Có câu chuyện cho cả hai vai trò sớm nhất: `v1.0.0` cho backend, `v1.1.0` cho AI | Saga, chaos, đối soát lùi ba tuần |
| Sau tuần 11 | Phần backend xong trọn trước. Có thêm nạp, rút để agent dùng | Nếu hết thời gian thì hướng AI không có gì |
| Song song, mỗi tuần một ít | Không lùi gì | Hai ngữ cảnh mỗi tuần, dễ dang dở cả hai |

Tác giả đang đi trước lịch khoảng năm tuần (tuần 6 xong ngày 07/10, lịch là 15/11), nên ba tuần thêm vẫn nằm trong khoảng dư đó.

## Giới hạn của nghiên cứu

- **Số liệu tuyển dụng mỏng.** Con số của ITviec là 20 tin hiển thị trong một ngày, đếm theo nhãn kỹ năng, không đọc từng mô tả. Các bài "kỹ năng AI Engineer 2026" là quan điểm của người viết, và vài bài là của công ty bán khóa học hoặc dịch vụ.
- **Chưa đọc được nguyên văn OWASP Agentic Top 10.** Trang chính thức chỉ có nút tải PDF. Tên các mục ASI01–ASI10 lấy từ các bài tóm tắt.
- **Chưa chạy thử thứ gì.** Chưa có dòng code nào kiểm chứng rằng SDK Python của MCP, pgvector trên PostgreSQL 18, hay model embedding chạy được trên máy dev 7 GB RAM.
- **Ước lượng chi phí chưa đo**, và giá model có thể đổi.
- **Không biết nhà tuyển dụng cụ thể nào tác giả nhắm tới.** Nếu là vị trí thiên về huấn luyện mô hình thì kế hoạch này không đủ.

## Nguồn

Tuyển dụng và kỹ năng:

- ITviec, *Tuyển dụng AI Engineer*, xem ngày 07/10/2026: https://itviec.com/it-jobs/ai-engineer
- CareerLink, *AI Engineer Fresher là gì*: https://www.careerlink.vn/cam-nang-viec-lam/tu-van-nghe-nghiep/ai-engineer-fresher-la-gi
- Indeed Việt Nam, việc làm "AI Agent": https://vn.indeed.com/q-ai-agent-vi%E1%BB%87c-l%C3%A0m.html
- Great Learning, *The Complete AI Agent Engineer Skills Stack You Need in 2026*: https://www.mygreatlearning.com/blog/the-complete-ai-agent-engineer-skills-stack-you-need-in-2026/
- AY Automate, *15 AI Engineer Skills Every Hire Should Have in 2026*: https://www.ayautomate.com/blog/ai-engineer-skills-2026
- Digital Applied, *AI Developer Hiring 2026: Skills That Actually Matter*: https://www.digitalapplied.com/blog/ai-developer-hiring-skills-that-matter-2026

Công nghệ:

- Model Context Protocol, *The 2026-07-28 Specification*: https://blog.modelcontextprotocol.io/posts/2026-07-28/
- Spring AI, *Stateless Streamable-HTTP MCP Servers* (bản 2.0.1): https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html
- MCP Java SDK: https://java.sdk.modelcontextprotocol.io/latest
- Anthropic, *Demystifying evals for AI agents*: https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents
- OWASP GenAI Security Project, *Top 10 for Agentic Applications for 2026* (09/12/2025): https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/
- Giskard, *OWASP ASI09 Human-Agent Trust Exploitation*: https://www.giskard.ai/glossary/owasp-asi09-human-agent-trust-exploitation-i2o37
- Greptime, *OpenTelemetry GenAI semantic conventions* (05/2026): https://www.greptime.com/blogs/2026-05-09-opentelemetry-genai-semantic-conventions
- dev.to, *OpenTelemetry's GenAI semantic conventions are not stable yet*: https://dev.to/azena-ai/opentelemetrys-genai-semantic-conventions-are-not-stable-yet-heres-what-actually-shipped-in-2026-3mke
- pgEdge, *pgvector 0.8.2 installation methods* (image `pgvector/pgvector:pg18`): https://docs.pgedge.com/pgvector/v0-8-2/additional-installation-methods/
- Google Cloud, *Multilingual E5 Small*: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/maas/e5/multilingual-e5-small
- BAAI, *BGE-M3*: https://huggingface.co/BAAI/bge-m3
- Uvik, *Python AI Agent Frameworks: The 2026 Comparison*: https://uvik.net/blog/python-ai-agent-frameworks/
