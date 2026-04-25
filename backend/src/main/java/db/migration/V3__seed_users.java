package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the four users listed in spec §5.2 with BCrypt-hashed passwords.
 *
 * <p>Java migration so the password hashes are computed at migration runtime
 * (BCrypt is non-deterministic — its salt rotates per call, which is what we
 * want — so we cannot hardcode hashes in a SQL migration without losing the
 * idempotency that Flyway's checksum guarantees).
 *
 * <p>The seed user_ids are derived deterministically from the username via
 * {@link UUID#nameUUIDFromBytes} so the same UUIDs are produced on every clean
 * boot — useful for tests and the §5.3 scenario CSV which references users by
 * a stable identity.
 */
public class V3__seed_users extends BaseJavaMigration {

    private record SeedUser(String username, String displayName, String password) {}

    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("u1", "Alice",   "alice123"),
            new SeedUser("u2", "Bob",     "bob123"),
            new SeedUser("u3", "Charlie", "charlie123"),
            new SeedUser("u4", "Diana",   "diana123")
    );

    @Override
    public void migrate(Context context) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

        try (PreparedStatement ps = context.getConnection().prepareStatement(
                "INSERT INTO users (user_id, username, display_name, password_hash) " +
                "VALUES (?, ?, ?, ?)")) {

            for (SeedUser user : SEED_USERS) {
                ps.setObject(1, deterministicUserId(user.username()));
                ps.setString(2, user.username());
                ps.setString(3, user.displayName());
                ps.setString(4, encoder.encode(user.password()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static UUID deterministicUserId(String username) {
        return UUID.nameUUIDFromBytes(("seed-user-" + username).getBytes());
    }
}
