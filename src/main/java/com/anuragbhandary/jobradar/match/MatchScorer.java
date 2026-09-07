package com.anuragbhandary.jobradar.match;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Scores a posting against the resume and the search strategy.
 *
 * <p>Pure and cheap: posting in, {@link MatchScore} out, no network and no clock
 * beyond today's date. Scoring all nine thousand postings takes about as long as
 * reading them from SQLite, which is what makes a ranked feed possible at all.
 *
 * <h2>What it is not</h2>
 * It is not a filter. {@link com.anuragbhandary.jobradar.filter.ScreeningService}
 * has already decided what is eligible, and it does that with hard rules and a
 * stated reason. This only orders what survived. A low score is "read this one
 * later", never "you cannot apply".
 *
 * <p>It also does not know whether he would enjoy the job. Every factor here is
 * something written down in the posting or the resume, and the moment a score
 * starts standing in for judgement it is worth less than the list it replaced.
 */
@Component
public class MatchScorer {

    // Out of 100. Skills dominate because they are the thing an interviewer will
    // actually test, and geography is heavy because of what it costs to be wrong.
    private static final int SKILLS_MAX = 40;
    private static final int EXPERIENCE_MAX = 25;
    private static final int GEOGRAPHY_MAX = 20;
    private static final int RECENCY_MAX = 10;
    private static final int SIGNALS_MAX = 5;

    private final ResumeModel resume;
    private final MatchProperties config;

    public MatchScorer(ResumeModel resume, MatchProperties config) {
        this.resume = resume;
        this.config = config;
    }

    public MatchScore score(Posting posting) {
        return score(posting, LocalDate.now());
    }

    /** Today is a parameter so the recency factor is testable. */
    MatchScore score(Posting posting, LocalDate today) {
        List<MatchScore.Factor> factors = List.of(
                skills(posting),
                experience(posting),
                geography(posting),
                recency(posting, today),
                signals(posting));

        int total = factors.stream().mapToInt(MatchScore.Factor::points).sum();
        return new MatchScore(total, factors);
    }

    /**
     * How much of what the posting names he already has.
     *
     * <p>Scored on the fraction covered rather than the count matched, so a
     * posting naming three technologies he knows all of beats one naming twenty
     * where he knows six. The count version rewards long adverts, which is a
     * property of the advert and not of the fit.
     */
    private MatchScore.Factor skills(Posting posting) {
        Set<String> wanted = TechVocabulary.found(
                posting.getTitle() + "\n" + nullSafe(posting.getDescriptionText()));
        if (wanted.isEmpty()) {
            // A posting naming no technology at all says nothing either way. Half
            // marks, rather than zero, which would push every vaguely written
            // advert to the bottom regardless of the job behind it.
            return new MatchScore.Factor("Skills", SKILLS_MAX / 2, SKILLS_MAX,
                    "the posting names no specific technology");
        }

        Set<String> known = knownTechnologies();
        List<String> have = wanted.stream().filter(known::contains).toList();
        List<String> missing = wanted.stream().filter(term -> !known.contains(term)).toList();

        // Fraction AND depth, blended.
        //
        // Fraction alone rewards a vague advert: a posting naming two
        // technologies he happens to know scored a perfect 40, while a rich
        // backend posting naming twelve where he knows nine scored 30. The first
        // real run put three security roles at the top of the feed on exactly
        // that, over the Java and Kafka postings the tool exists to find.
        //
        // Counting matches alone has the opposite fault and rewards length. So
        // coverage is most of the score and absolute depth is the rest, which
        // means "all of two" loses to "nine of twelve" without a long advert
        // winning by being long.
        float coverage = (float) have.size() / wanted.size();
        float depth = Math.min(1f, have.size() / 8f);
        int points = Math.round(SKILLS_MAX * (0.7f * coverage + 0.3f * depth));

        String detail = missing.isEmpty()
                ? "you have all " + wanted.size() + " technologies it names"
                : "you have %d of %d; missing %s".formatted(
                        have.size(), wanted.size(),
                        String.join(", ", missing.subList(0, Math.min(4, missing.size()))));
        return new MatchScore.Factor("Skills", points, SKILLS_MAX, detail);
    }

    /**
     * Whether the years it asks for are years he can defend.
     *
     * <p>Note what happens when a posting states nothing: a middling score, not a
     * high one. An unstated requirement is unknown, and treating silence as
     * "entry level welcome" is the exact mistake the years extractor exists to
     * prevent.
     */
    private MatchScore.Factor experience(Posting posting) {
        Integer years = posting.getMinYears();
        if (years == null) {
            return new MatchScore.Factor("Experience", EXPERIENCE_MAX / 2, EXPERIENCE_MAX,
                    "not screened for years yet");
        }
        if (years < 0) {
            return new MatchScore.Factor("Experience", Math.round(EXPERIENCE_MAX * 0.7f),
                    EXPERIENCE_MAX, "states no requirement, which is not the same as none");
        }

        int comfortable = config.comfortableYears();
        if (years <= comfortable) {
            return new MatchScore.Factor("Experience", EXPERIENCE_MAX, EXPERIENCE_MAX,
                    "asks for " + years + "+ years, which you have");
        }
        // Falls away fast. Two years over is a stretch worth trying; five is a
        // different job with the same title.
        int over = years - comfortable;
        int points = Math.max(0, Math.round(EXPERIENCE_MAX * (1f - over / 4f)));
        return new MatchScore.Factor("Experience", points, EXPERIENCE_MAX,
                "asks for " + years + "+ years, " + over + " more than you can evidence");
    }

    private MatchScore.Factor geography(Posting posting) {
        int preference = config.preferenceFor(posting.getCountry());
        int points = Math.round(GEOGRAPHY_MAX * preference / 100f);
        return new MatchScore.Factor("Location", points, GEOGRAPHY_MAX,
                posting.getCountry() == null
                        ? "no country worked out"
                        : posting.getCountry().name().toLowerCase(Locale.ROOT)
                                + ", weighted " + preference + " of 100");
    }

    /**
     * Age, because a requisition open for four months is usually filled.
     *
     * <p>An absent date scores as mid rather than as fresh. Boards that publish no
     * date would otherwise sweep the top of the feed by saying nothing.
     */
    private MatchScore.Factor recency(Posting posting, LocalDate today) {
        LocalDate posted = posting.getPostedDate();
        if (posted == null) {
            return new MatchScore.Factor("Freshness", RECENCY_MAX / 2, RECENCY_MAX,
                    "the board publishes no date");
        }
        long days = ChronoUnit.DAYS.between(posted, today);
        if (days <= config.freshDays()) {
            return new MatchScore.Factor("Freshness", RECENCY_MAX, RECENCY_MAX,
                    days <= 1 ? "posted today" : "posted " + days + " days ago");
        }
        if (days >= config.staleDays()) {
            return new MatchScore.Factor("Freshness", 0, RECENCY_MAX,
                    "open " + days + " days, probably filled");
        }
        float remaining = 1f - (days - config.freshDays())
                / (float) (config.staleDays() - config.freshDays());
        return new MatchScore.Factor("Freshness", Math.round(RECENCY_MAX * remaining),
                RECENCY_MAX, "posted " + days + " days ago");
    }

    /**
     * The two things a posting can say that change the odds.
     *
     * <p>Small on purpose. Both are reported and neither decides anything
     * elsewhere in the tool, and a factor worth five points cannot quietly become
     * the reason something ranks first.
     */
    private MatchScore.Factor signals(Posting posting) {
        List<String> found = new ArrayList<>();
        int points = 0;
        if (posting.isGraduateSignal()) {
            points += 3;
            found.add("names new graduates");
        }
        if (posting.getSponsorshipSignal() != null && !posting.getSponsorshipSignal().isBlank()) {
            points += 2;
            found.add("mentions sponsorship");
        }
        return new MatchScore.Factor("Signals", points, SIGNALS_MAX,
                found.isEmpty() ? "says nothing about graduates or visas"
                        : String.join(", ", found));
    }

    /**
     * Everything he can claim, as vocabulary terms.
     *
     * <p>Run through {@link TechVocabulary} rather than compared as raw strings so
     * both sides are normalised the same way. "PostgreSQL" in the resume and
     * "Postgres" in the advert are the same skill, and a string comparison says
     * they are not.
     */
    private Set<String> knownTechnologies() {
        StringBuilder text = new StringBuilder();
        resume.skills().forEach(group -> text.append(String.join(" ", group.items())).append(' '));
        resume.allTags().forEach(tag -> text.append(tag).append(' '));
        resume.projects().forEach(project -> {
            text.append(project.stack()).append(' ');
            project.bullets().forEach(bullet -> text.append(bullet.text()).append(' '));
        });
        if (resume.experience() != null) {
            resume.experience().forEach(job ->
                    job.bullets().forEach(bullet -> text.append(bullet.text()).append(' ')));
        }
        return new LinkedHashSet<>(TechVocabulary.found(text.toString()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
