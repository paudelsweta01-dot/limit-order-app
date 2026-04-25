package com.sweta.limitorder.persistence;

import com.sweta.limitorder.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that V1 schema, V2 symbol seed, and V3 user seed migrations
 * applied cleanly and produced the expected state. This is the trivial
 * repository test required by Phase 2 task 2.4.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MigrationSmokeTest {

    @Autowired
    private SymbolRepository symbols;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v2SeedsTheFiveSpecSymbols() {
        List<Symbol> all = symbols.findAll();
        assertThat(all)
                .extracting(Symbol::getSymbol)
                .containsExactlyInAnyOrder("AAPL", "MSFT", "GOOGL", "TSLA", "AMZN");
        assertThat(symbols.findById("AAPL"))
                .hasValueSatisfying(s -> {
                    assertThat(s.getName()).isEqualTo("Apple Inc.");
                    assertThat(s.getRefPrice()).isEqualByComparingTo(new BigDecimal("180.00"));
                });
    }

    @Test
    void v3SeedsTheFourSpecUsers() {
        assertThat(users.findAll())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("u1", "u2", "u3", "u4");
        assertThat(users.findByUsername("u1"))
                .hasValueSatisfying(u -> assertThat(u.getDisplayName()).isEqualTo("Alice"));
        assertThat(users.findByUsername("u2"))
                .hasValueSatisfying(u -> assertThat(u.getDisplayName()).isEqualTo("Bob"));
    }

    @Test
    void seedUserPasswordsAreBcryptHashedAndVerify() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        User alice = users.findByUsername("u1").orElseThrow();
        assertThat(alice.getPasswordHash()).startsWith("$2");
        assertThat(encoder.matches("alice123", alice.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong", alice.getPasswordHash())).isFalse();
    }

    @Test
    void ordersBookPartialIndexExists() {
        Map<String, Object> idx = jdbc.queryForMap(
                "SELECT indexname, indexdef FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND indexname = 'orders_book_idx'");
        assertThat((String) idx.get("indexdef"))
                .contains("(symbol, side, price, created_at)")
                .contains("WHERE")
                .contains("OPEN")
                .contains("PARTIAL");
    }

    @Test
    void marketEventOutboxNotifyTriggerExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*)::int FROM pg_trigger " +
                        "WHERE tgname = 'market_event_outbox_notify' AND NOT tgisinternal",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
