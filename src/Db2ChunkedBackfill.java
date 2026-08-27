import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** DB2-only bounded UPDATE/COMMIT executor for large existing-item backfills. */
final class Db2ChunkedBackfill {
    static final int CHUNKING_THRESHOLD_ROWS = 10000;
    static final int DEFAULT_CHUNK_ROWS = 250000;
    static final int MIN_CHUNK_ROWS = 1000;

    interface CommitGuard {
        void verify(long committedRows) throws Exception;
    }

    private Db2ChunkedBackfill() { }

    static boolean shouldUse(BackfillService backfill, BackfillWritePlan plan) {
        return "DB2".equals(backfill.databaseDisplayName())
                && plan.plannedFillableRows > CHUNKING_THRESHOLD_ROWS;
    }

    static BackfillResult apply(BackfillService backfill,
                                BackfillWritePlan plan,
                                CommitGuard guard) throws Exception {
        Connection db = backfill.directConnection();
        boolean originalAutoCommit = db.getAutoCommit();
        long totalUpdated = 0L;
        int chunkRows = initialChunkRows(plan.plannedFillableRows);
        int chunkNumber = 0;
        try {
            if (originalAutoCommit) db.setAutoCommit(false);

            String table = backfill.qualifiedTable(plan.rootFingerprint.tableName);
            String baseUpdate = BackfillService.buildUpdateSql(table, plan.durationSql);

            System.out.println("  Write mode        : DB2 chunked COMMIT");
            System.out.println("  Initial chunk     : " + chunkRows + " row(s)");

            while (true) {
                long chunkStarted = Timing.start();
                int updated;
                String sql = buildChunkUpdateSql(baseUpdate, chunkRows);
                try {
                    Statement statement = db.createStatement();
                    try {
                        updated = statement.executeUpdate(sql);
                    } finally {
                        statement.close();
                    }
                    db.commit();
                } catch (SQLException e) {
                    rollbackQuietly(db);
                    if (isTransactionLogFull(e) && chunkRows > MIN_CHUNK_ROWS) {
                        int smaller = Math.max(MIN_CHUNK_ROWS, chunkRows / 2);
                        System.err.println("WARNING: DB2 transaction log full for chunk size "
                                + chunkRows + "; current chunk was rolled back. Retrying with "
                                + smaller + " row(s).");
                        chunkRows = smaller;
                        continue;
                    }
                    if (totalUpdated > 0) {
                        throw new CliException("Chunked DB2 backfill already committed " + totalUpdated
                                + " row(s), then failed before completion: " + safeSqlMessage(e)
                                + ". Policy assignment was not started. Re-run the same backfill;"
                                + " already dated rows are protected by the NULL guards.", 6);
                    }
                    throw e;
                }

                if (updated < 0) {
                    throw new CliException("DB2 JDBC driver returned an unknown update count for a"
                            + " chunked backfill. Policy assignment was not started.",
                            totalUpdated > 0 ? 6 : 3);
                }

                chunkNumber++;
                totalUpdated += updated;
                System.out.println("  Chunk " + chunkNumber + " committed : " + updated
                        + " row(s); total " + totalUpdated
                        + progress(plan.plannedFillableRows, totalUpdated)
                        + " / " + Timing.since(chunkStarted));

                if (updated > 0 && guard != null) {
                    try {
                        guard.verify(totalUpdated);
                    } catch (Exception e) {
                        BackfillWorkflow.printEmbeddedProblem("Chunk post-COMMIT safety guard", e);
                        throw new CliException("DB2 backfill committed " + totalUpdated
                                + " row(s), but the post-COMMIT safety guard failed."
                                + " Policy assignment was not started; review the current policy,"
                                + " ItemType and root metadata before retrying.", 6);
                    }
                }

                if (updated < chunkRows) break;
            }

            long remaining = queryLong(db,
                    "SELECT COUNT(*) FROM " + table
                            + " WHERE ICM$RETENTIONDATE IS NULL"
                            + " AND ICM$AUTODELETEDATE IS NULL");
            if (remaining != 0) {
                throw new CliException("Chunked DB2 backfill committed " + totalUpdated
                        + " row(s), but " + remaining
                        + " row(s) still have NULL retention/auto-delete metadata."
                        + " Policy assignment was not started; review concurrent writes and retry.", 6);
            }

            if (totalUpdated > Integer.MAX_VALUE) {
                throw new CliException("Chunked DB2 backfill committed more than "
                        + Integer.MAX_VALUE + " row(s); final state is complete but the current"
                        + " runtime cannot represent the update count safely. Review before assignment.", 6);
            }
            return new BackfillResult((int) totalUpdated, remaining);
        } finally {
            backfill.restoreConnectionState(db, originalAutoCommit);
        }
    }

    static int initialChunkRows(long plannedRows) {
        if (plannedRows <= 0) return MIN_CHUNK_ROWS;
        long bounded = Math.min((long) DEFAULT_CHUNK_ROWS,
                Math.max((long) MIN_CHUNK_ROWS, plannedRows));
        return (int) bounded;
    }

    static String buildChunkUpdateSql(String baseUpdate, int chunkRows) {
        if (chunkRows <= 0) {
            throw new IllegalArgumentException("chunkRows must be positive");
        }
        return baseUpdate + " FETCH FIRST " + chunkRows + " ROWS ONLY";
    }

    static boolean isTransactionLogFull(SQLException error) {
        SQLException current = error;
        while (current != null) {
            if (current.getErrorCode() == -964) return true;
            String message = current.getMessage();
            if (message != null && message.contains("SQLCODE=-964")) return true;
            current = current.getNextException();
        }
        return false;
    }

    private static long queryLong(Connection db, String sql) throws SQLException {
        Statement statement = db.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                if (!rs.next()) throw new SQLException("COUNT query returned no row");
                return rs.getLong(1);
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private static void rollbackQuietly(Connection db) {
        try { db.rollback(); } catch (Exception ignored) { }
    }

    private static String safeSqlMessage(SQLException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getName() : message;
    }

    private static String progress(long planned, long updated) {
        if (planned <= 0) return "";
        double percent = Math.min(100.0d, (updated * 100.0d) / planned);
        return String.format(java.util.Locale.ROOT, " / %.1f%% of planned", percent);
    }
}
