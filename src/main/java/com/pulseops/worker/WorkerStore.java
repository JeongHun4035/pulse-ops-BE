package com.pulseops.worker;

import com.pulseops.check.HttpProbe.Outcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Component
public class WorkerStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    public WorkerStore(DataSource source) { this.source = source; this.jdbc = new JdbcTemplate(source); }
    public List<Long> dueIds(Instant cutoff) {
        return jdbc.queryForList("SELECT id FROM sites WHERE enabled AND next_check_at <= ? ORDER BY next_check_at, id",
            Long.class, Timestamp.from(cutoff));
    }
    public Claim claim(long id, Instant cutoff) throws SQLException {
        Connection connection = source.getConnection();
        boolean locked = false;
        try {
            connection.setAutoCommit(false);
            // Short transaction + row lock serializes claim with PATCH/DELETE. SKIP LOCKED avoids waiting.
            try (var select = connection.prepareStatement("SELECT url FROM sites WHERE id = ? AND enabled AND next_check_at <= ? FOR UPDATE SKIP LOCKED")) {
                select.setLong(1, id); select.setTimestamp(2, Timestamp.from(cutoff));
                try (var rows = select.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); connection.close(); return null; }
                    String url = rows.getString(1);
                    try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                        lock.setLong(1, id);
                        try (var result = lock.executeQuery()) { result.next(); locked = result.getBoolean(1); }
                    }
                    connection.commit();
                    connection.setAutoCommit(true);
                    if (locked) return new Claim(connection, id, url);
                    connection.close(); return null;
                }
            }
        } catch (SQLException e) {
            try { connection.rollback(); } finally {
                if (locked) unlock(connection, id);
                connection.close();
            }
            throw e;
        }
    }
    private static void unlock(Connection c, long id) throws SQLException {
        try (var statement = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, id); statement.execute();
        } catch (SQLException e) {
            // Never return a session with a potentially held advisory lock to the pool.
            c.abort(Runnable::run); throw e;
        }
    }
    public static final class Claim implements AutoCloseable {
        private final Connection connection;
        private final long id;
        private final String url;
        private Claim(Connection c, long id, String url) { connection = c; this.id = id; this.url = url; }
        public String url() { return url; }
        public boolean transactionOpen() throws SQLException { return !connection.getAutoCommit(); }
        public void complete(Outcome outcome, Instant completedAt) throws SQLException {
            connection.setAutoCommit(false);
            try {
                // Site may have been deleted, disabled or rescheduled while HTTP was in flight.
                try (var select = connection.prepareStatement("SELECT interval_seconds, next_check_at FROM sites WHERE id = ? FOR UPDATE")) {
                    select.setLong(1, id);
                    try (var rows = select.executeQuery()) {
                        if (!rows.next()) { connection.commit(); return; }
                        int interval = rows.getInt(1);
                        Instant next = rows.getTimestamp(2).toInstant();
                        try (var insert = connection.prepareStatement("INSERT INTO check_results(site_id, checked_at, status, http_status_code, response_time_ms, error_message) VALUES (?, ?, ?, ?, ?, ?)")) {
                            insert.setLong(1, id); insert.setTimestamp(2, Timestamp.from(outcome.checkedAt()));
                            insert.setString(3, outcome.status()); insert.setObject(4, outcome.httpStatusCode());
                            insert.setObject(5, outcome.responseTimeMs()); insert.setString(6, outcome.errorMessage()); insert.executeUpdate();
                        }
                        if (!next.isAfter(outcome.checkedAt())) {
                            try (var update = connection.prepareStatement("UPDATE sites SET next_check_at = ? WHERE id = ?")) {
                                update.setTimestamp(1, Timestamp.from(completedAt.plusSeconds(interval)));
                                update.setLong(2, id); update.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        }
        @Override public void close() throws SQLException {
            try { unlock(connection, id); } finally { connection.close(); }
        }
    }
}
