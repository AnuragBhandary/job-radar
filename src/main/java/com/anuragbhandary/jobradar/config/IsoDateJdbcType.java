package com.anuragbhandary.jobradar.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

/**
 * Stores SQL DATE as an ISO-8601 string rather than epoch milliseconds.
 *
 * <p>SQLite has no date type, so sqlite-jdbc stores a {@code LocalDate} by
 * converting it to a {@code java.sql.Date} and writing the epoch millisecond
 * value of local midnight. That conversion uses the JVM's default timezone in
 * both directions, which makes the stored value only meaningful to a JVM in the
 * same zone that wrote it.
 *
 * <p>Concretely: 2026-08-31 written in IST is stored as 1788114600000, which is
 * 18:30 on 2026-08-30 in UTC. Read back by a JVM running in UTC - CI, or the
 * Docker image - the same row yields 2026-08-30. Every posted date silently
 * shifts by a day, and only outside the machine it was developed on.
 *
 * <p>An ISO string has no timezone to get wrong, and has the side benefit of
 * making the database readable with the {@code sqlite3} CLI. PostgreSQL keeps
 * its native {@code date} column, because this replaces how the value is bound,
 * not how the column is declared.
 */
public final class IsoDateJdbcType implements JdbcType {

    public static final IsoDateJdbcType INSTANCE = new IsoDateJdbcType();

    private IsoDateJdbcType() {
    }

    @Override
    public int getJdbcTypeCode() {
        return Types.DATE;
    }

    @Override
    public String getFriendlyName() {
        return "DATE (ISO-8601 string)";
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                    throws SQLException {
                st.setString(index, asIsoString(value, javaType, options));
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                    throws SQLException {
                st.setString(name, asIsoString(value, javaType, options));
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override
            protected X doExtract(ResultSet rs, int index, WrapperOptions options)
                    throws SQLException {
                return fromIsoString(rs.getString(index), javaType, options);
            }

            @Override
            protected X doExtract(CallableStatement st, int index, WrapperOptions options)
                    throws SQLException {
                return fromIsoString(st.getString(index), javaType, options);
            }

            @Override
            protected X doExtract(CallableStatement st, String name, WrapperOptions options)
                    throws SQLException {
                return fromIsoString(st.getString(name), javaType, options);
            }
        };
    }

    /**
     * Reads an ISO date, tolerating two older shapes: a full timestamp string,
     * and the epoch-millisecond form rows written before this type existed use.
     * The millisecond form is interpreted in the system zone because that is the
     * zone it was written in - the bug being fixed, honoured on the way out.
     */
    private static LocalDate parse(String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
            return java.time.Instant.ofEpochMilli(Long.parseLong(trimmed))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
        }
        return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
    }

    private static <X> String asIsoString(X value, JavaType<X> javaType, WrapperOptions options) {
        return javaType.unwrap(value, LocalDate.class, options).toString();
    }

    private static <X> X fromIsoString(String value, JavaType<X> javaType, WrapperOptions options) {
        if (value == null) {
            return null;
        }
        return javaType.wrap(parse(value), options);
    }
}
