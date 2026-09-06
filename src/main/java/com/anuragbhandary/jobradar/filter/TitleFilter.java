package com.anuragbhandary.jobradar.filter;

import com.anuragbhandary.jobradar.config.AppProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a title describes a job worth reading.
 *
 * <p>Exclusion runs before inclusion, and wins. "Senior Software Engineer"
 * contains "software", and a filter that checked inclusion first would keep it.
 *
 * <p>Seniority levels get their own treatment: "Engineer II" and "Engineer 2"
 * are both levels, but matching "ii" as a substring would also reject "Hawaii"
 * and any title containing a double-i. They are matched as standalone words only.
 */
@Component
public class TitleFilter {

    /**
     * Gender tags, stripped before anything else reads the title.
     *
     * <p>Dutch and German ads carry "(v/m/x)", "(m/v)", "(m/w/d)", "(m,f,x)" or
     * "(all genders)". Two of those contain a standalone "v", which the old
     * seniority pattern read as the Roman numeral five: Coolblue's Dutch postings
     * were being rejected as senior roles. Nothing here is a false alarm today
     * only because those two happen to be facilities jobs the title filter would
     * reject anyway - a Dutch posting called "Software Engineer (v/m/x)" would
     * have been discarded silently, in a target country.
     *
     * <p>Removing the tag outright is clearer than defending every pattern
     * downstream against it.
     */
    private static final Pattern GENDER_TAG = Pattern.compile(
            "\\(\\s*(?:[a-z]\\s*[/,]\\s*){1,3}[a-z]\\s*\\)|\\(\\s*all genders\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Roman seniority levels, which are unambiguous anywhere in a title.
     *
     * <p>Roman I is omitted because "SDE I" is a target. V is omitted too, but
     * for a different reason: it is a real level and it is also the "v" in
     * "(v/m/x)". It is matched by {@link #LEVEL_AFTER_ROLE} instead, where a role
     * word has to precede it.
     *
     * <p>Word boundaries keep "ii" out of "Hawaii".
     */
    private static final Pattern ROMAN_LEVEL =
            Pattern.compile("\\b(ii|iii|iv)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A level in Arabic notation, or a Roman V, attached to a role noun.
     *
     * <p>Arabic 1 is omitted because "Engineer 1" is a target. Everything above
     * is not: MongoDB advertises "Software Engineer 2" in Dublin, which read as
     * entry-level until the Arabic form was handled.
     *
     * <p>Requiring a role word in front is what stopped the bare digit matching
     * anything that happened to contain one. "Tech Recruitment Business Partner -
     * 6 month contract" was being rejected for seniority on the strength of the
     * 6, and the trailing guard covers the case that would actually have cost
     * something: "Software Engineer, 2 Year Rotational Programme" is a graduate
     * fast-track, and the years extractor goes to some trouble to protect that
     * exact phrasing further down the pipeline. It never got the chance, because
     * the title filter rejected it first.
     */
    private static final Pattern LEVEL_AFTER_ROLE = Pattern.compile(
            "\\b(?:engineer|developer|sde|swe|scientist|analyst|designer|associate"
                    + "|programmer|consultant|specialist|architect|manager|tse)\\b"
                    + "[\\s,\\-\u2013\u2014()]*"
                    + "\\b(?:v|[2-9])\\b"
                    + "(?!\\s*[-\u2013]?\\s*(?:year|yr|month|mo|week|day))",
            Pattern.CASE_INSENSITIVE);

    private final AppProperties.Screening screening;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    public TitleFilter(AppProperties properties) {
        this.screening = properties.screening();
    }

    public FilterVerdict screen(String title) {
        if (title == null || title.isBlank()) {
            return FilterVerdict.reject("no title");
        }
        String normalised = GENDER_TAG.matcher(title.toLowerCase(Locale.ROOT))
                .replaceAll(" ");

        String excluded = firstMatch(normalised, screening.titleExclude());
        if (excluded != null) {
            return FilterVerdict.reject("title excluded on '" + excluded + "'");
        }
        if (ROMAN_LEVEL.matcher(normalised).find()
                || LEVEL_AFTER_ROLE.matcher(normalised).find()) {
            return FilterVerdict.reject("title excluded on seniority level");
        }

        String included = firstMatch(normalised, screening.titleInclude());
        if (included == null) {
            return FilterVerdict.reject("title is not a software role");
        }
        return FilterVerdict.accept();
    }

    /** True if the title carries an explicit graduate or early-career signal. */
    public boolean hasGraduateSignal(String title, String description) {
        String haystack = ((title == null ? "" : title) + " "
                + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        return screening.graduateSignals().stream().anyMatch(haystack::contains);
    }

    private String firstMatch(String haystack, List<String> needles) {
        if (needles == null) {
            return null;
        }
        for (String needle : needles) {
            if (pattern(needle).matcher(haystack).find()) {
                return needle.trim();
            }
        }
        return null;
    }

    private Pattern pattern(String needle) {
        return patterns.computeIfAbsent(needle, n -> {
            String trimmed = n.trim();
            String prefix = Character.isLetterOrDigit(trimmed.charAt(0)) ? "\\b" : "";
            String suffix = Character.isLetterOrDigit(trimmed.charAt(trimmed.length() - 1))
                    ? "\\b" : "";
            return Pattern.compile(prefix + Pattern.quote(trimmed) + suffix,
                    Pattern.CASE_INSENSITIVE);
        });
    }
}
