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
     * Seniority levels, in either notation.
     *
     * <p>Roman I and Arabic 1 are both omitted, because "SDE I" and "Engineer 1"
     * are targets. Everything above is not: MongoDB advertises "Software
     * Engineer 2" in Dublin, which read as entry-level until the Arabic form was
     * added here.
     */
    private static final Pattern SENIORITY_LEVEL =
            Pattern.compile("\\b(ii|iii|iv|v|[2-9])\\b", Pattern.CASE_INSENSITIVE);

    private final AppProperties.Screening screening;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    public TitleFilter(AppProperties properties) {
        this.screening = properties.screening();
    }

    public FilterVerdict screen(String title) {
        if (title == null || title.isBlank()) {
            return FilterVerdict.reject("no title");
        }
        String normalised = title.toLowerCase(Locale.ROOT);

        String excluded = firstMatch(normalised, screening.titleExclude());
        if (excluded != null) {
            return FilterVerdict.reject("title excluded on '" + excluded + "'");
        }
        if (SENIORITY_LEVEL.matcher(normalised).find()) {
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
