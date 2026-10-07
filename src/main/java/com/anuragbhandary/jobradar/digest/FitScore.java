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
 *   <li>30 for the applicant's skills named in the title or description,</li>
 *   <li>20 for an entry-level title (graduate, junior, fresher, "Engineer I",
 *       associate), 10 for an internship, and up to 30 off for a senior one,</li>
 *   <li>20 for the stated years (zero scores best, not stated sits between one
 *       and two),</li>
 *   <li>25 for the lane, in the applicant's order: remote for a foreign employer,
 *       then Mumbai, then the rest of India, then a primary relocation country,
 *       then a secondary one,</li>
 *   <li>10 for freshness, and 5 for new-graduate wording.</li>
 * </ul>
 *
 * <p>The level term exists because skill words alone ranked by length of
 * description: on 2026-10-01 an "Associate Distinguished Engineer" scored 74 and
 * an 8-year role 75, while Deliveroo's new-grad role scored 51 and a "Java
 * Developer - Fresher" 52. Entries under {@code min-fit} are left out of the
 * file and counted.
 * The skills are configuration ({@code job-radar.ranking.skills}), both resumes'
 * worth, so a resume change is a config edit.
 */
@Component
public class FitScore {

    /** Configuration: the skill words, and how many entries get a full block. */
    @ConfigurationProperties(prefix = "job-radar.ranking")
    public record Properties(List<String> skills, Integer fullDetail, Integer minFit, String modelPath) {

        public int fullDetailOrDefault() {
            return fullDetail == null ? 60 : fullDetail;
        }

        public int minFitOrDefault() {
            return minFit == null ? 0 : minFit;
        }
    }

    /**
     * An entry-level title. A Roman "I" counts only on its own ("Engineer I", not
     * "Engineer II" or "Engineer in Test").
     */
    private static final Pattern ENTRY_TITLE = Pattern.compile(
            "\\b(?:graduate|new[\\s-]?grads?|fresher|freshers|entry[\\s-]level|early[\\s-]careers?"
                    + "|junior|jr\\b|trainee|apprentice\\w*|campus"
                    + "|associate\\s+(?:software|data|engineer|developer|analyst|ml|machine|ai|backend"
                    + "|back-end|cloud|qa|quality|test|devops|site)"
                    + "|(?:engineer|developer|analyst|scientist|sde|swe)\\s*[-,]?\\s*(?:i|1|l1)(?![\\w+#])"
                    + "|level\\s*1\\b|\\bl1\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTERN_TITLE = Pattern.compile(
            "\\b(?:intern|interns|internship|co-?op|placement\\s+student)\\b", Pattern.CASE_INSENSITIVE);

    /** Seniority the title filter lets through, because it is in a longer title. */
    private static final Pattern SENIOR_TITLE = Pattern.compile(
            "\\b(?:senior|sr\\b|staff|principal|principle|lead|leader|head|manager|director|architect"
                    // Salesforce's ladder: "Software Engineering PMTS" (2026-10-07).
                    + "|distinguished|expert|chief|vp|pmts|smts|lmts)\\b"
                    + "|\\b(?:iii|iv)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Seniority stated in the description when the years are not. On 2026-10-07,
     * 34 of 143 candidates needed far more than a fresher has, and most said so
     * only in words: "expert-level experience", "managing and mentoring
     * engineering teams", "operating at Staff, Principal or a comparable level".
     * A score, not a rejection: these are judgement, not stated facts.
     */
    private static final Pattern SENIOR_WORDING = Pattern.compile(
            "\\b(?:expert[\\s-]level|extensive\\s+(?:hands-on\\s+)?experience|deep\\s+(?:expertise|experience)"
                    + "|(?:managing|leading)\\s+and\\s+mentoring|mentor(?:ing)?\\s+(?:junior|other|engineers|the\\s+team)"
                    + "|operating\\s+at\\s+(?:a\\s+)?(?:staff|principal|senior)|technical\\s+leadership"
                    + "|proven\\s+(?:track\\s+record|experience\\s+as\\s+a)|demonstrated\\s+experience\\s+as\\s+a"
                    + "|seasoned|strong\\s+production\\s+(?:software[\\s-])?engineering\\s+experience)\\b",
            Pattern.CASE_INSENSITIVE);

    static final int SENIOR_WORDING_PENALTY = 15;

    /** A second-level title. Google's "II" is its entry level, so not there. */
    private static final Pattern SECOND_LEVEL_TITLE = Pattern.compile(
            "\\b(?:ii|2)\\s*$|\\b(?:engineer|developer|analyst|scientist)\\s+(?:ii|2)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A score and the reasons for it, for the line under the entry's heading. */
    public record Fit(int score, List<String> matched, String summary) {
    }

    private final Properties properties;
    private final CountryStrategy strategy;
    private final Map<String, Pattern> patterns;
    /** Learned weights ({@code calibrate --learn}); empty means the hand weights below. */
    private final java.util.Optional<FitModel> model;

    public FitScore(Properties properties, CountryStrategy strategy) {
        this.properties = properties;
        this.strategy = strategy;
        List<String> skills = properties.skills() == null ? List.of() : properties.skills();
        // "backend|back-end" is one skill with two spellings, counted once.
        this.patterns = skills.stream().collect(Collectors.toMap(
                s -> s.split("\\|")[0].trim(), FitScore::groupPattern, (a, b) -> a,
                java.util.LinkedHashMap::new));
        this.model = FitModel.load(properties.modelPath() == null ? null
                : java.nio.file.Path.of(properties.modelPath()));
    }

    /** The learned score before freshness: the model's chance, then the lane in his order. */
    public static int learnedScore(double probability, int lanePoints) {
        return (int) Math.round(75 * probability) + (int) Math.round(lanePoints * 0.6);
    }

    /** The lane's hand points (0-25), which the learned score adds on top. */
    public int lanePoints(Posting p) {
        return lane(p);
    }

    /** Whether the score comes from learned weights. */
    public boolean learned() {
        return model.isPresent();
    }

    /** The hand-weighted score, whatever the model: {@code calibrate} compares the two. */
    public int handScore(Posting p, LocalDate today) {
        return parts(p, today).handScore();
    }

    /**
     * The model's inputs, in {@link FitModel#FEATURES} order. Freshness is left
     * out: a decision is scored long after it was made, when every posting is
     * old, so the past says nothing about it. It is added after, as before.
     */
    public double[] features(Posting p) {
        Parts parts = parts(p, null);
        Integer years = parts.years();
        return new double[] {
                1,
                Math.min(6, parts.matched().size()) / 6.0,
                flag(parts.level() == Level.ENTRY), flag(parts.level() == Level.INTERN),
                flag(parts.level() == Level.SECOND), flag(parts.level() == Level.SENIOR),
                flag(years != null && years == 0), flag(years != null && years == 1),
                flag(years != null && years == 2), flag(years == null),
                flag(parts.seniorWording() != null), flag(p.isGraduateSignal()), flag(parts.hidden()),
                flag(p.getSource() != null && switch (p.getSource()) {
                    case ARBEITNOW, JOBICY, WE_WORK_REMOTELY, HACKER_NEWS -> true;
                    default -> false;
                }),
                flag(p.getSource() != null && switch (p.getSource()) {
                    case AMAZON, GOOGLE, APPLE, EIGHTFOLD -> true;
                    default -> false;
                }),
                flag(DigestWriter.frontendSignal(p.getDescriptionText()) != null)};
    }

    private static double flag(boolean on) {
        return on ? 1 : 0;
    }

    /** How many of the best entries get a full block in the openings file. */
    public int fullDetail() {
        return properties.fullDetailOrDefault();
    }

    /** Where {@code calibrate --learn} saves the weights, or null when unset. */
    public java.nio.file.Path modelPath() {
        return properties.modelPath() == null ? null : java.nio.file.Path.of(properties.modelPath());
    }

    /** Entries scoring under this are counted, not listed. */
    public int minFit() {
        return properties.minFitOrDefault();
    }

    /** Everything the score is made of, computed once. */
    private record Parts(List<String> matched, Level level, Integer years, int lane, int fresh,
            boolean hidden, String seniorWording, int handScore) {
    }

    private Parts parts(Posting p, LocalDate today) {
        String text = ((p.getTitle() == null ? "" : p.getTitle()) + "\n"
                + (p.getDescriptionText() == null ? "" : p.getDescriptionText())).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Pattern> skill : patterns.entrySet()) {
            if (skill.getValue().matcher(text).find()) {
                matched.add(skill.getKey());
            }
        }
        matched.sort(String::compareTo);
        int skills = Math.min(30, matched.size() * 5);

        Level level = level(p);

        // Negative is the extractor's "could not read it", which is not stated.
        Integer years = p.getMinYears() == null || p.getMinYears() < 0 ? null : p.getMinYears();
        int experience = years == null ? 10 : switch (years) {
            case 0 -> 20;
            case 1 -> 15;
            case 2 -> 6;
            default -> 0;
        };

        int lane = lane(p);

        int fresh = 0;
        if (today != null && p.getPostedDate() != null) {
            long age = ChronoUnit.DAYS.between(p.getPostedDate(), today);
            fresh = age <= 3 ? 10 : age <= 7 ? 7 : age <= 14 ? 4 : 0;
        }
        int graduate = p.isGraduateSignal() ? 5 : 0;
        // He applies on the employer's own site only, which a hidden employer rules
        // out unless the review can work out who it is.
        boolean hidden = com.anuragbhandary.jobradar.domain.Employer.hidesEmployer(
                p.getSource(), p.getBoardToken());

        // Only when the title has not already been marked down for the same thing.
        String seniorWording = null;
        if (level != Level.SENIOR && p.getDescriptionText() != null) {
            java.util.regex.Matcher senior = SENIOR_WORDING.matcher(p.getDescriptionText());
            if (senior.find()) {
                seniorWording = senior.group().toLowerCase(Locale.ROOT);
            }
        }

        int hand = Math.max(0, Math.min(100,
                skills + level.points + experience + lane + fresh + graduate - (hidden ? 20 : 0)
                        - (seniorWording == null ? 0 : SENIOR_WORDING_PENALTY)));
        return new Parts(matched, level, years, lane, fresh, hidden, seniorWording, hand);
    }

    public Fit fit(Posting p, LocalDate today) {
        Parts parts = parts(p, today);
        // Learned: 75 points from the model's balanced chance of a pick, up to 15
        // for the lane in his order (the hand lane points scaled), and 10 for
        // freshness, so the scale stays near the one min-fit was set on.
        int score = model.map(m -> learnedScore(m.probability(features(p)), parts.lane())
                        + parts.fresh())
                .orElse(parts.handScore());
        List<String> matched = parts.matched();
        Level level = parts.level();
        Integer years = parts.years();
        String summary = "%d skill%s%s, %s%s, %s".formatted(matched.size(), matched.size() == 1 ? "" : "s",
                matched.isEmpty() ? "" : " (" + String.join(", ", matched.subList(0, Math.min(6, matched.size())))
                        + (matched.size() > 6 ? ", ..." : "") + ")",
                (level.words.isEmpty() ? "" : level.words + ", ") + (parts.hidden() ? "employer hidden, " : "")
                        + (parts.seniorWording() == null ? "" : "senior wording (\"" + parts.seniorWording() + "\"), "),
                years == null ? "years not stated" : years + (years == 1 ? " year" : " years"),
                laneName(p));
        return new Fit(Math.max(0, Math.min(100, score)), matched, summary);
    }

    /** What the title says about the level, and what that is worth. */
    enum Level {
        ENTRY(20, "entry-level title"),
        INTERN(10, "internship"),
        NEUTRAL(0, ""),
        SECOND(-10, "level II title"),
        SENIOR(-30, "senior title");

        final int points;
        final String words;

        Level(int points, String words) {
            this.points = points;
            this.words = words;
        }
    }

    static Level level(Posting p) {
        String title = p.getTitle() == null ? "" : p.getTitle().replace('_', ' ');
        if (SENIOR_TITLE.matcher(title).find()) {
            return Level.SENIOR;
        }
        if (INTERN_TITLE.matcher(title).find()) {
            return Level.INTERN;
        }
        if (ENTRY_TITLE.matcher(title).find()) {
            return Level.ENTRY;
        }
        if (p.getSource() != com.anuragbhandary.jobradar.domain.Source.GOOGLE
                && SECOND_LEVEL_TITLE.matcher(title).find()) {
            return Level.SECOND;
        }
        return Level.NEUTRAL;
    }

    /** The applicant's own order, which is not the strategy's weight order. */
    private int lane(Posting p) {
        StrategicClass lane = p.getStrategicClass();
        if (lane == null) {
            return 8;
        }
        return switch (lane) {
            case INTERNATIONAL_REMOTE -> 25;
            case INDIA_HOME -> 23;
            case INDIA_OTHER -> 18;
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
