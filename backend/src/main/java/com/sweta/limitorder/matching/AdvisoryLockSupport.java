package com.sweta.limitorder.matching;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Per-symbol Postgres advisory lock — the project's single concurrency control
 * primitive (see architecture §4.3).
 *
 * <p>{@code pg_advisory_xact_lock} is acquired on a hash of the symbol and
 * automatically released on transaction commit or rollback. Two transactions
 * touching the same symbol — on the same JVM or on different backend
 * instances — serialize through the lock; transactions on different symbols
 * proceed in parallel.
 *
 * <p>Calling this without an active transaction would acquire a lock that is
 * never released by anything except the connection going back to the pool.
 * That's a recipe for cluster-wide hangs, so we fail loudly instead.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryLockSupport {

    private final JdbcTemplate jdbc;

    public void lockForSymbol(String symbol) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "pg_advisory_xact_lock requires an active transaction; " +
                            "wrap the caller in @Transactional.");
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", symbol);
    }
}
