package com.anuragbhandary.jobradar.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Widens the {@code CHECK} constraints Hibernate writes for enum columns.
 *
 * <h2>The problem this exists for</h2>
 * Hibernate maps {@code @Enumerated(STRING)} to
 * {@code varchar check (source in ('GREENHOUSE','ASHBY',...))}, listing the values
 * that existed when the table was created. SQLite <strong>cannot alter a CHECK
 * constraint</strong> - there is no {@code ALTER TABLE ... DROP CONSTRAINT} - and
 * {@code ddl-auto: update} only ever adds columns and tables, so it will not
 * notice. Adding one value to {@link com.anuragbhandary.jobradar.domain.Source}
 * therefore compiles, starts, fetches, and fails at the first insert with a
 * constraint violation on a table that looks correct.
 *
 * <p>That has now happened twice in this project. The documented workaround was to
 * delete the database and re-fetch nine thousand postings, which also throws away
 * every {@code firstSeen} date - the only field that cannot be recovered by
 * fetching again.
 *
 * <h2>Why it runs before Spring</h2>
 * It has to happen before Hibernate opens the schema, and every clean hook for
 * that is a framework interface that runs after the {@code EntityManagerFactory}
 * is built. Plain JDBC in {@code main} is uglier and is actually explicit about
 * the ordering, which for a migration is worth more.
 *
 * <p>The rebuild is the standard SQLite table-swap - rename, recreate, copy, drop -
 * and it patches the stored DDL rather than regenerating it, so nothing about the
 * table changes except the list inside the {@code CHECK}. Column order is
 * preserved by construction, which is what makes {@code INSERT ... SELECT *} safe
 * here when it would not be against a hand-written replacement.
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    /** Table, column, and the values that column must accept. */
    private record EnumColumn(String table, String column, List<String> values) {
    }

    private SchemaMigrator() {
    }

    /**
     * Brings every enum CHECK constraint up to date.
     *
     * <p>A no-op on a database that is already correct, and on one that does not
     * exist yet - Hibernate will create it with the current values.
     */
    public static void migrate(String jdbcUrl) {
        List<EnumColumn> columns = List.of(
                new EnumColumn("posting", "source", names(
                        com.anuragbhandary.jobradar.domain.Source.class)),
                new EnumColumn("posting", "country", names(
                        com.anuragbhandary.jobradar.domain.Country.class)),
                new EnumColumn("posting", "verdict", names(
                        com.anuragbhandary.jobradar.domain.Verdict.class)),
                new EnumColumn("posting", "status", names(
                        com.anuragbhandary.jobradar.domain.PostingStatus.class)),
                new EnumColumn("board_token", "source", names(
                        com.anuragbhandary.jobradar.domain.Source.class)),
                new EnumColumn("application_attempt", "status", names(
                        com.anuragbhandary.jobradar.apply.AttemptStatus.class)));

        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            for (EnumColumn column : columns) {
                migrateColumn(connection, column);
            }
        } catch (SQLException e) {
            // Loud, and fatal. A migration that half-ran and was logged as a
            // warning is how a database ends up in a state nobody can reason
            // about - and this one holds the only firstSeen dates there are.
            throw new IllegalStateException(
                    "Schema migration failed against " + jdbcUrl + ": " + e.getMessage(), e);
        }
    }

    private static void migrateColumn(Connection connection, EnumColumn column)
            throws SQLException {

        String ddl = tableDdl(connection, column.table());
        if (ddl == null) {
            return; // Table not created yet. Hibernate will do it correctly.
        }

        String patched = widen(ddl, column.column(), column.values());
        if (patched == null) {
            return; // Already accepts every value, or has no CHECK on this column.
        }

        log.warn("{}.{} rejects one or more current enum values - rebuilding the table",
                column.table(), column.column());
        rebuild(connection, column.table(), patched);
        log.info("Rebuilt {} with {} accepting {}",
                column.table(), column.column(), String.join(", ", column.values()));
    }

    /**
     * Returns the DDL with the value list replaced, or null if no change is needed.
     *
     * <p>Package-private and pure so the regex - the part most likely to be wrong -
     * is tested against real Hibernate output rather than against a live database.
     */
    static String widen(String ddl, String columnName, List<String> required) {
        // Hibernate writes: check (source in ('A','B')) - the spacing varies by
        // dialect version, hence the loose whitespace.
        Pattern pattern = Pattern.compile(
                "check\\s*\\(\\s*" + Pattern.quote(columnName) + "\\s+in\\s*\\(([^)]*)\\)\\s*\\)",
                Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(ddl);
        if (!matcher.find()) {
            return null; // No CHECK on this column; nothing to widen.
        }

        Set<String> present = new LinkedHashSet<>();
        for (String value : matcher.group(1).split(",")) {
            String cleaned = value.trim().replaceAll("^'|'$", "");
            if (!cleaned.isEmpty()) {
                present.add(cleaned);
            }
        }
        if (present.containsAll(required)) {
            return null;
        }

        // Union, not replacement. A value in the table that is no longer in the
        // enum would fail the copy, and a migration that cannot copy the rows is
        // worse than one that leaves a stale value permitted.
        Set<String> union = new LinkedHashSet<>(present);
        union.addAll(required);

        String replacement = "check (" + columnName + " in ("
                + String.join(",", union.stream().map(v -> "'" + v + "'").toList()) + "))";
        return new StringBuilder(ddl)
                .replace(matcher.start(), matcher.end(), replacement)
                .toString();
    }

    /**
     * The rename-copy-drop swap, in one transaction.
     *
     * <p>Indexes are recreated explicitly. They follow the table through a
     * {@code RENAME} and are destroyed with it by the {@code DROP}, so a rebuild
     * that forgets them leaves a correct table that has quietly become a full
     * table scan on every query.
     */
    private static void rebuild(Connection connection, String table, String patchedDdl)
            throws SQLException {

        List<String> indexes = indexDdl(connection, table);
        String temporary = table + "_migrating";

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=off");
            statement.execute("ALTER TABLE " + table + " RENAME TO " + temporary);
            // The patched DDL still names the original table, which is now free.
            statement.execute(patchedDdl);
            statement.execute("INSERT INTO " + table + " SELECT * FROM " + temporary);
            statement.execute("DROP TABLE " + temporary);
            for (String index : indexes) {
                statement.execute(index);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=on");
            }
        }
    }

    private static String tableDdl(Connection connection, String table) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> indexDdl(Connection connection, String table) throws SQLException {
        List<String> statements = new ArrayList<>();
        try (var ps = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? "
                        + "AND sql IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    statements.add(rs.getString(1));
                }
            }
        }
        return statements;
    }

    private static <E extends Enum<E>> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(value -> value.name().toUpperCase(Locale.ROOT))
                .toList();
    }
}
