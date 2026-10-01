package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import com.anuragbhandary.jobradar.strategy.RelocationTier;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Orders the review file so the likeliest fits are read first.
 *
 * <p>Discovery took the review window from a few dozen candidates to several
 * hundred, more than a review can judge one by one. This is a rough sort, not a
 * judgement: the review still reads each role it keeps. Out of 100:
 * <ul>
 *   <li>40 for the applicant's skills named in the title or description,</li>
 *   <li>20 for the stated years (none or zero scores best),</li>
 *   <li>25 for the lane, in the applicant's order: remote for a foreign employer,
 *       then Mumbai, then the rest of India, then a primary relocation country,
 *       then a secondary one,</li>
 *   <li>10 for freshness, and 5 for new-graduate wording.</li>
 * </ul>
 * The skills are configuration ({@code job-radar.ranking.skills}), both resumes'
 * worth, so a resume change is a config edit.
 */
@Component
public class FitScore {

    /** Configuration: the skill words, and how many entries get a full block. */
    @ConfigurationProperties(prefix = "job-radar.ranking")
    public record Properties(List<String> skills, Integer fullDetail) {

        public int fullDetailOrDefault() {
            return fullDetail == null ? 60 : fullDetail;
        }
    }

    /** A score and the reasons for it, for the line under the entry's heading. */
    public record Fit(int score, List<String> matched, String summary) {
    }

    private final Properties properties;
    private final CountryStrategy strategy;
    private final Map<String, Pattern> patterns;

    public FitScore(Properties properties, CountryStrategy strategy) {
        this.properties = properties;
        this.strategy = strategy;
        List<String> skills = properties.skills() == null ? List.of() : properties.skills();
        // "backend|back-end" is one skill with two spellings, counted once.
        this.patterns = skills.stream().collect(Collectors.toMap(
                s -> s.split("\\|")[0].trim(), FitScore::groupPattern, (a, b) -> a,
                java.util.LinkedHashMap::new));
    }

    /** How many of the best entries get a full block in the openings file. */
    public int fullDetail() {
        return properties.fullDetailOrDefault();
    }

    public Fit fit(Posting p, LocalDate today) {
        String text = ((p.getTitle() == null ? "" : p.getTitle()) + "\n"
                + (p.getDescriptionText() == null ? "" : p.getDescriptionText())).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Pattern> skill : patterns.entrySet()) {
            if (skill.getValue().matcher(text).find()) {
                matched.add(skill.getKey());
            }
        }
        matched.sort(String::compareTo);
        int skills = Math.min(40, matched.size() * 6);

        // Negative is the extractor's "could not read it", which is not stated.
        Integer years = p.getMinYears() == null || p.getMinYears() < 0 ? null : p.getMinYears();
        int experience = years == null ? 14 : switch (years) {
            case 0 -> 20;
            case 1 -> 15;
            case 2 -> 6;
            default -> 0;
        };

        int lane = lane(p);

        int fresh = 0;
        if (p.getPostedDate() != null) {
            long age = ChronoUnit.DAYS.between(p.getPostedDate(), today);
            fresh = age <= 3 ? 10 : age <= 7 ? 7 : age <= 14 ? 4 : 0;
        }
        int graduate = p.isGraduateSignal() ? 5 : 0;

        int score = Math.min(100, skills + experience + lane + fresh + graduate);
        String summary = "%d skill%s%s, %s, %s".formatted(matched.size(), matched.size() == 1 ? "" : "s",
                matched.isEmpty() ? "" : " (" + String.join(", ", matched.subList(0, Math.min(6, matched.size())))
                        + (matched.size() > 6 ? ", ..." : "") + ")",
                years == null ? "years not stated" : years + (years == 1 ? " year" : " years"),
                laneName(p));
        return new Fit(score, matched, summary);
    }

    /** The applicant's own order, which is not the strategy's weight order. */
    private int lane(Posting p) {
        StrategicClass lane = p.getStrategicClass();
        if (lane == null) {
            return 8;
        }
        return switch (lane) {
            case INTERNATIONAL_REMOTE -> 25;
            case INDIA_HOME -> 22;
            case INDIA_OTHER -> 20;
            case INTERNATIONAL_RELOCATION -> strategy.tierFor(p.getCountryCode()) == RelocationTier.PRIMARY ? 14 : 10;
            case UNCLASSIFIED -> 5;
        };
    }

    private static String laneName(Posting p) {
        StrategicClass lane = p.getStrategicClass();
        if (lane == null) {
            return "lane unknown";
        }
        return switch (lane) {
            case INTERNATIONAL_REMOTE -> "remote from India";
            case INDIA_HOME -> "Mumbai";
            case INDIA_OTHER -> "India";
            case INTERNATIONAL_RELOCATION -> "relocation to " + p.getCountryCode();
            case UNCLASSIFIED -> "lane unknown";
        };
    }

    /** Any spelling in "a|b|c", each as a whole word: "go" is not found in "google". */
    private static Pattern groupPattern(String group) {
        List<String> alternatives = new ArrayList<>();
        for (String spelling : group.split("\\|")) {
            String s = spelling.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            String prefix = Character.isLetterOrDigit(s.charAt(0)) ? "(?<![a-z0-9])" : "";
            String suffix = Character.isLetterOrDigit(s.charAt(s.length() - 1)) ? "(?![a-z0-9])" : "";
            alternatives.add(prefix + Pattern.quote(s) + suffix);
        }
        return Pattern.compile(String.join("|", alternatives));
    }
}
