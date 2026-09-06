package com.anuragbhandary.jobradar.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.unique.AlterTableUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.UniqueKey;

/**
 * SQLite dialect that actually creates composite unique constraints, and that
 * reports constraint violations as constraint violations.
 *
 * <p>Two gaps in Hibernate's community {@code SQLiteDialect} are patched here.
 *
 * <h2>1. Composite unique keys were silently discarded</h2>
 *
 * <p>The stock dialect overrides {@code getAlterTableToAddUniqueKeyCommand} to
 * return an empty string, because SQLite has no {@code ALTER TABLE ... ADD
 * CONSTRAINT}. Single-column uniqueness still works - it is inlined on the column -
 * but a multi-column unique key vanishes with no error and no warning.
 *
 * <p>That matters because {@code Posting}'s natural key is the triple
 * (source, boardToken, externalId). Without it, every re-fetch would insert a
 * duplicate row instead of matching the existing posting, and change detection -
 * the entire point of this tool - would report every posting as NEW forever.
 *
 * <p>Neither delegate Hibernate ships is sufficient on its own:
 * {@code CreateTableUniqueDelegate} inlines the key into {@code CREATE TABLE},
 * which is right for {@code ddl-auto: create} but falls back to {@code ALTER TABLE}
 * on the migrate path that {@code ddl-auto: update} uses; and
 * {@code AlterTableUniqueIndexDelegate} only emits an index when the key contains
 * a nullable column, which ours does not. So the index form is emitted here
 * unconditionally. {@code CREATE UNIQUE INDEX} is valid on both schema paths and
 * on SQLite and PostgreSQL alike, so swapping to PostgreSQL stays a change of the
 * {@code hibernate.dialect} property in application.yml.
 *
 * <h2>2. Constraint violations arrived untyped</h2>
 *
 * <p>The stock dialect does not translate SQLite's constraint error codes, so a
 * unique violation reaches callers as an opaque {@code JpaSystemException} rather
 * than Spring's {@code DataIntegrityViolationException}.
 */
public class SqliteDialect extends org.hibernate.community.dialect.SQLiteDialect {

    /** SQLITE_CONSTRAINT. Extended codes carry a sub-code in the high bits. */
    private static final int SQLITE_CONSTRAINT = 19;

    /** SQLITE_CONSTRAINT_UNIQUE - a UNIQUE constraint, as opposed to NOT NULL or FK. */
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    /** SQLITE_CONSTRAINT_PRIMARYKEY. */
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;

    /**
     * SQLite names the violated constraint only inside the message text, as
     * "UNIQUE constraint failed: posting.source, posting.board_token". There is no
     * structured field for it, so it has to be scraped.
     */
    private static final Pattern CONSTRAINT_COLUMNS =
            Pattern.compile("constraint failed:\\s*(.+?)\\s*\\)?$");

    private final UniqueDelegate uniqueDelegate = new UniqueIndexDelegate(this);

    @Override
    public UniqueDelegate getUniqueDelegate() {
        return uniqueDelegate;
    }

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        return (sqlException, message, sql) -> {
            int code = sqlException.getErrorCode();
            if (code != SQLITE_CONSTRAINT && (code & 0xFF) != SQLITE_CONSTRAINT) {
                // Not a constraint problem; returning null defers to Hibernate's
                // default handling rather than mislabelling it.
                return null;
            }
            ConstraintViolationException.ConstraintKind kind =
                    (code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY)
                            ? ConstraintViolationException.ConstraintKind.UNIQUE
                            : ConstraintViolationException.ConstraintKind.OTHER;
            return new ConstraintViolationException(
                    message, sqlException, sql, kind, extractConstraintName(sqlException));
        };
    }

    private static String extractConstraintName(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return null;
        }
        Matcher m = CONSTRAINT_COLUMNS.matcher(message.trim());
        return m.find() ? m.group(1) : null;
    }

    /**
     * Expresses every unique key as a unique index, on both the create and the
     * migrate path. The {@code if not exists} / {@code if exists} guards keep the
     * migrate path idempotent, which SQLite needs because it cannot introspect
     * its own constraints well enough for Hibernate to skip an existing one.
     */
    private static final class UniqueIndexDelegate extends AlterTableUniqueDelegate {

        private final Dialect dialect;

        private UniqueIndexDelegate(Dialect dialect) {
            super(dialect);
            this.dialect = dialect;
        }

        @Override
        public String getAlterTableToAddUniqueKeyCommand(
                UniqueKey uniqueKey, Metadata metadata, SqlStringGenerationContext context) {
            String columns = uniqueKey.getColumns().stream()
                    .map(this::quoted)
                    .collect(Collectors.joining(", "));
            return "create unique index if not exists " + uniqueKey.getName()
                    + " on " + context.format(uniqueKey.getTable().getQualifiedTableName())
                    + " (" + columns + ")";
        }

        @Override
        public String getAlterTableToDropUniqueKeyCommand(
                UniqueKey uniqueKey, Metadata metadata, SqlStringGenerationContext context) {
            return "drop index if exists " + uniqueKey.getName();
        }

        private String quoted(Column column) {
            return column.getQuotedName(dialect);
        }
    }
}
