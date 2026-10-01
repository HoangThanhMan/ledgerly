package dev.ledgerly;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Lớp cha của integration test trong {@code ledger-app}: Spring context đầy đủ, PostgreSQL và Kafka thật.
 *
 * <p>Spring cache context theo cấu hình, nên các lớp con không thêm cấu hình riêng sẽ dùng chung một context. Lớp
 * con có cấu hình riêng (ví dụ {@code @MockitoBean}) tạo context mới, nhưng vẫn dùng chung container nhờ
 * {@link TestcontainersConfiguration}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {}
