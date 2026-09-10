package com.anuragbhandary.jobradar.domain;

import java.util.Locale;
import java.util.Set;

/**
 * ISO-3166 alpha-2 codes, and the bridge to the legacy {@link Country} enum.
 *
 * <p>Countries are stored as codes rather than as enum values for one reason:
 * {@link Country} is mapped to a SQLite {@code CHECK} constraint, and SQLite
 * cannot alter one. Adding Australia to a five-value enum is therefore a table
 * rebuild - see {@link com.anuragbhandary.jobradar.config.SchemaMigrator}, which
 * exists because that has already happened twice here. A {@code varchar} column
 * holding "AU" needs no migration at all.
 *
 * <p>Validity is checked against {@link Locale#getISOCountries()} rather than
 * against a list maintained here. The instruction was not to invent country
 * codes, and the JDK already knows which ones exist; a hand-kept list would drift
 * and would quietly accept a typo as a new country.
 *
 * <p>Display names come from the JDK too, in {@link Locale#UK} because the rest
 * of the tool is written in British English and the browser session is opened
 * with {@code en-GB}.
 */
public final class CountryCodes {

    private CountryCodes() {
    }

    /** The one country the applicant may work in without a permit. */
    public static final String INDIA = "IN";

    private static final Set<String> VALID = Set.of(
            Locale.getISOCountries());

    /** True for a real ISO-3166 alpha-2 code. Null and blank are not codes. */
    public static boolean isValid(String code) {
        return code != null && VALID.contains(code.toUpperCase(Locale.ROOT));
    }

    /**
     * Uppercased, or null.
     *
     * <p>Returns null rather than throwing for anything that is not a real code.
     * Callers store the result, and an unknown country must be stored as unknown -
     * "XX" would be a fabricated fact sitting in a column that later phases will
     * reason about.
     */
    public static String normalise(String code) {
        if (code == null) {
            return null;
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return VALID.contains(upper) ? upper : null;
    }

    /** "DE" to "Germany". The code itself when the JDK has no name for it. */
    public static String displayName(String code) {
        String normalised = normalise(code);
        if (normalised == null) {
            return "Unknown";
        }
        String name = Locale.of("", normalised).getDisplayCountry(Locale.UK);
        return name.isBlank() ? normalised : name;
    }

    public static boolean isIndia(String code) {
        return INDIA.equals(normalise(code));
    }

    /**
     * The code behind a legacy enum value, or null.
     *
     * <p>{@link Country#REMOTE} and {@link Country#OTHER} have no code, and that
     * is the point: the first is a working arrangement wearing a country's
     * clothes, and the second is fifty countries in a trench coat. Both migrate
     * by re-screening from the posting's own location text, not by mapping.
     */
    public static String fromLegacy(Country country) {
        if (country == null) {
            return null;
        }
        return switch (country) {
            case INDIA -> "IN";
            case GERMANY -> "DE";
            case IRELAND -> "IE";
            case NETHERLANDS -> "NL";
            case REMOTE, OTHER -> null;
        };
    }

    /**
     * The legacy enum value for a classified posting.
     *
     * <p>Kept populated so that everything written before this phase keeps
     * working unchanged: {@code FieldMapper}'s sponsorship and authorisation
     * derivations, {@code Compensation.bandFor}, {@code SalaryFloorAdvisor},
     * {@code MatchScorer} and the country filter on the jobs page all still read
     * {@link Posting#getCountry()}. Retiring the enum is a later phase; breaking
     * the answer path in this one is not on the table.
     *
     * <p>International remote maps to {@link Country#REMOTE} because that is what
     * it meant before - a role worked from Mumbai on no permit - which keeps the
     * two sponsorship answers exactly as they are today.
     */
    public static Country toLegacy(String code, StrategicClass strategicClass) {
        if (strategicClass == StrategicClass.INTERNATIONAL_REMOTE) {
            return Country.REMOTE;
        }
        String normalised = normalise(code);
        if (normalised == null) {
            return Country.OTHER;
        }
        return switch (normalised) {
            case "IN" -> Country.INDIA;
            case "DE" -> Country.GERMANY;
            case "IE" -> Country.IRELAND;
            case "NL" -> Country.NETHERLANDS;
            default -> Country.OTHER;
        };
    }
}
