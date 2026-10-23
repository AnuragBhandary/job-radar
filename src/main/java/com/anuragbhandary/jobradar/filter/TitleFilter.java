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

    /**
     * The upper end of a range title: "Analyst/Senior Analyst", "Member / Sr.
     * Member", "Engineer/Lead Engineer". Group 1 is what comes before the slash
     * (back to the start or the previous slash), group 2 the seniority word.
     */
    private static final Pattern RANGE_UPPER = Pattern.compile(
            "(^|/)([^/]*?)\\s*/\\s*(senior|sr\\.?|lead|principal)\\s+");

    /**
     * A slug's range title, whose slash was lost: "analyst senior analyst data
     * analytics", "member senior member tech ops". The same word on both sides of
     * the seniority word is what marks it.
     */
    private static final Pattern SLUG_RANGE = Pattern.compile(
            "\\b([a-z]+) (senior|sr|lead|principal) \\1\\b");

    /**
     * A lower end that is itself mid-level: "Middle/Senior", "Advanced/Senior",
     * "Experienced/Senior", Brazil's "PL/SR" (pleno). The first diff of the range
     * rule let all of these through.
     */
    private static final Pattern MID_LEVEL = Pattern.compile(
            "\\b(middle|mid|mid-level|advanced|experienced|pl|pleno|intermediate|regular)\\b");

    private final AppProperties.Screening screening;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    public TitleFilter(AppProperties properties) {
        this.screening = properties.screening();
    }

    public FilterVerdict screen(String title) {
        return screen(title, true);
    }

    /**
     * @param requireRoleWord false where the title is free text that may not name
     *                        a role at all; exclusions still apply
     */
    public FilterVerdict screen(String title, boolean requireRoleWord) {
        if (title == null || title.isBlank()) {
            return FilterVerdict.reject("no title");
        }
        // Underscores become spaces first. Bosch India prefixes titles with codes
        // like "IN_RBIN_Senior Engineer_Lab engineer", and "_" is a word character,
        // so no \b boundary exists before "Senior" and every exclusion missed.
        String normalised = GENDER_TAG.matcher(
                        title.toLowerCase(Locale.ROOT).replace('_', ' '))
                .replaceAll(" ");

        normalised = withoutRangeUpper(normalised);

        String excluded = firstMatch(normalised, screening.titleExclude());
        if (excluded != null) {
            return FilterVerdict.reject("title excluded on '" + excluded + "'");
        }
        if (ROMAN_LEVEL.matcher(normalised).find()
                || LEVEL_AFTER_ROLE.matcher(normalised).find()) {
            return FilterVerdict.reject("title excluded on seniority level");
        }

        String domain = firstMatch(normalised, screening.domainExclude());
        if (domain != null && firstMatch(normalised, screening.domainKeep()) == null) {
            return FilterVerdict.reject("finance, risk or operations role: '" + domain + "'");
        }

        String included = firstMatch(normalised, screening.titleInclude(), true);
        if (included == null && requireRoleWord) {
            return FilterVerdict.reject("title is not a software role");
        }
        return FilterVerdict.accept();
    }

    /**
     * The title with the senior end of a range taken out, when the lower end is
     * itself clean: "analyst/senior analyst - p&l" reads as "analyst/analyst -
     * p&l", so the domain and other words still count. D. E. Shaw, Societe
     * Generale and the analytics firms advertise one opening at two levels this
     * way, and "senior" rejected the junior half too (until 2026-10-23). A range
     * that starts senior ("Senior Analyst/Lead Analyst") is left alone.
     */
    String withoutRangeUpper(String normalised) {
        java.util.regex.Matcher m = RANGE_UPPER.matcher(normalised);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String lower = m.group(2);
            boolean lowerIsClean = !lower.isBlank()
                    && firstMatch(lower, screening.titleExclude()) == null
                    && !MID_LEVEL.matcher(lower).find();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    lowerIsClean ? m.group(1) + lower + "/" : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A URL slug's words with any range title given its slash back, so the
     * range rule above can see it: "analyst senior analyst data analytics"
     * becomes "analyst/senior analyst data analytics".
     */
    public static String slugWithRanges(String slugText) {
        return slugText == null ? null
                : SLUG_RANGE.matcher(slugText.toLowerCase(Locale.ROOT)).replaceAll("$1/$2 $1");
    }

    /**
     * Google's title with its level II taken out. At Google "Software Engineer II"
     * is the entry level (L3, "1 year of experience" on 2026-10-01) and III is the
     * first experienced one, so II says nothing the stated years do not.
     */
    public static String withoutGoogleEntryLevel(String title) {
        return title == null ? null
                : title.replaceAll("(?i)\\bII\\b", " ").replaceAll("\\s+", " ").trim();
    }

    /** The first of {@code words} found in the title as a whole word, or null. */
    public String firstMatchIn(String title, List<String> words) {
        if (title == null) {
            return null;
        }
        String normalised = GENDER_TAG.matcher(
                        title.toLowerCase(Locale.ROOT).replace('_', ' '))
                .replaceAll(" ");
        return firstMatch(normalised, words);
    }

    /** True if the title carries an explicit graduate or early-career signal. */
    public boolean hasGraduateSignal(String title, String description) {
        String haystack = ((title == null ? "" : title) + " "
                + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        return screening.graduateSignals().stream().anyMatch(haystack::contains);
    }

    private String firstMatch(String haystack, List<String> needles) {
        return firstMatch(haystack, needles, false);
    }

    /**
     * @param plural also match the word with an "s" after it. Only the role words
     *               ask for this: Zoho titles its openings "Software Developers",
     *               "QA Engineers", "Web Developers" (2026-10-23), and a whole-word
     *               "developer" missed all three. Exclusions stay exact.
     */
    private String firstMatch(String haystack, List<String> needles, boolean plural) {
        if (needles == null) {
            return null;
        }
        for (String needle : needles) {
            if (pattern(needle, plural).matcher(haystack).find()) {
                return needle.trim();
            }
        }
        return null;
    }

    private Pattern pattern(String needle, boolean plural) {
        return patterns.computeIfAbsent((plural ? "s|" : "|") + needle, key -> {
            String trimmed = needle.trim();
            String prefix = Character.isLetterOrDigit(trimmed.charAt(0)) ? "\\b" : "";
            boolean wordEnd = Character.isLetterOrDigit(trimmed.charAt(trimmed.length() - 1));
            // Short words keep exact matching: "bi" would take German "bis" ("befr.
            // bis 31.12.2027", "(BIS)"), as the first diff of this change showed.
            String suffix = !wordEnd ? "" : plural && trimmed.length() >= 4 ? "s?\\b" : "\\b";
            return Pattern.compile(prefix + Pattern.quote(trimmed) + suffix,
                    Pattern.CASE_INSENSITIVE);
        });
    }
}
