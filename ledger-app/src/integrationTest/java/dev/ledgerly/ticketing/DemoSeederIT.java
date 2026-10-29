package dev.ledgerly.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ticketing.internal.application.DemoSeeder;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** The demo data is seeded at startup when asked for, and a second run adds nothing. */
@TestPropertySource(properties = "ledgerly.demo.seed=true")
class DemoSeederIT extends AbstractIntegrationTest {

    private final DemoSeeder seeder;
    private final JdbcClient jdbc;

    DemoSeederIT(@Autowired DemoSeeder seeder, @Autowired JdbcClient jdbc) {
        this.seeder = seeder;
        this.jdbc = jdbc;
    }

    @Test
    void seedsOneDemoEventOnce() {
        seeder.seed();

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM events e JOIN users u ON u.id = e.organizer_id
                        WHERE u.email = :email
                        """)
                        .param("email", DemoSeeder.ORGANIZER_EMAIL)
                        .query(Long.class)
                        .single())
                .isOne();
        Map<String, @Nullable Object> vip =
                jdbc.sql("""
                        SELECT tc.total_qty, tc.price FROM ticket_classes tc
                        JOIN events e ON e.id = tc.event_id JOIN users u ON u.id = e.organizer_id
                        WHERE u.email = :email AND tc.name = 'VIP'
                        """).param("email", DemoSeeder.ORGANIZER_EMAIL).query().singleRow();
        assertThat(vip).containsEntry("total_qty", 1).containsEntry("price", 2_000_000L);
    }
}
