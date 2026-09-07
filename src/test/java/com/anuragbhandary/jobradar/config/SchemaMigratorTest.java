package com.anuragbhandary.jobradar.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigratorTest {

    /** Hibernate's actual output, spacing and all. */
    private static final String REAL_DDL =
            "create table posting (id integer, source varchar(32) not null "
            + "check (source in ('GREENHOUSE','ASHBY','LEVER','SMARTRECRUITERS')), "
            + "title varchar(512) not null, primary key (id))";

    @Test
    @DisplayName("a missing enum value widens the list")
    void widensWhenAValueIsMissing() {
        String patched = SchemaMigrator.widen(REAL_DDL, "source",
                List.of("GREENHOUSE", "ASHBY", "LEVER", "SMARTRECRUITERS", "WORKDAY"));

        assertThat(patched).contains("'WORKDAY'").contains("'GREENHOUSE'");
        assertThat(patched).contains("title varchar(512) not null");
    }

    @Test
    @DisplayName("a database already accepting every value is left alone")
    void noChangeWhenAlreadyCorrect() {
        assertThat(SchemaMigrator.widen(REAL_DDL, "source",
                List.of("GREENHOUSE", "ASHBY"))).isNull();
        assertThat(SchemaMigrator.widen(REAL_DDL, "source",
                List.of("GREENHOUSE", "ASHBY", "LEVER", "SMARTRECRUITERS"))).isNull();
    }

    @Test
    @DisplayName("a value in the table but no longer in the enum is kept")
    void unionsRatherThanReplaces() {
        // Replacing the list would make the INSERT ... SELECT fail on any row
        // still carrying the retired value, and a migration that cannot copy the
        // rows is worse than one that permits a stale value.
        String patched = SchemaMigrator.widen(REAL_DDL, "source", List.of("WORKDAY"));

        assertThat(patched).contains("'GREENHOUSE'").contains("'WORKDAY'");
    }

    @Test
    void noCheckConstraintIsNotAnError() {
        assertThat(SchemaMigrator.widen(
                "create table posting (id integer, source varchar(32))",
                "source", List.of("WORKDAY"))).isNull();
    }

    @Test
    void onlyTouchesTheNamedColumn() {
        String ddl = "create table posting (source varchar(32) "
                + "check (source in ('GREENHOUSE')), country varchar(32) "
                + "check (country in ('INDIA')))";

        String patched = SchemaMigrator.widen(ddl, "country", List.of("INDIA", "REMOTE"));

        assertThat(patched).contains("check (source in ('GREENHOUSE'))");
        assertThat(patched).contains("'REMOTE'");
    }

    // -----------------------------------------------------------------------
    // Against a real SQLite file, because the swap is the risky half.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the rebuild keeps every row and every index")
    void rebuildPreservesDataAndIndexes(@TempDir Path tempDir) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("test.db");

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("create table posting (id integer primary key, "
                    + "source varchar(32) not null check (source in ('GREENHOUSE')), "
                    + "title varchar(512), verdict varchar(32), status varchar(32), "
                    + "country varchar(32))");
            statement.execute("create index ix_posting_verdict on posting (verdict)");
            statement.execute("insert into posting (id, source, title) "
                    + "values (1, 'GREENHOUSE', 'Backend Engineer')");
        }

        SchemaMigrator.migrate(url);

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "select source, title from posting where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("title")).isEqualTo("Backend Engineer");
            }

            // An index destroyed by the DROP and not recreated leaves a correct
            // table that has silently become a full scan.
            try (ResultSet rs = statement.executeQuery(
                    "select name from sqlite_master where type='index' "
                            + "and name='ix_posting_verdict'")) {
                assertThat(rs.next()).isTrue();
            }

            // The point of the exercise: a value the old constraint rejected.
            statement.execute("insert into posting (id, source, title) "
                    + "values (2, 'WORKDAY', 'Another Role')");
        }
    }

    @Test
    @DisplayName("a database with no tables yet is a no-op, not a failure")
    void emptyDatabaseIsFine(@TempDir Path tempDir) {
        SchemaMigrator.migrate("jdbc:sqlite:" + tempDir.resolve("fresh.db"));
    }
}
