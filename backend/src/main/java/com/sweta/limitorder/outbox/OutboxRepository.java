package com.sweta.limitorder.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<OutboxRow> MAPPER = (rs, n) -> new OutboxRow(
            rs.getLong("id"),
            rs.getString("channel"),
            rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    public Optional<OutboxRow> findById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT id, channel, payload::text, created_at " +
                            "  FROM market_event_outbox WHERE id = ?",
                    MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM market_event_outbox WHERE created_at < ?",
                OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC));
    }
}
