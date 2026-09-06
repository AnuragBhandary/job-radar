package com.anuragbhandary.jobradar.apply.resume;

import com.anuragbhandary.jobradar.domain.Posting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Chooses which true things to say first.
 *
 * <p><strong>Tailoring here is selection and ordering, never generation.</strong>
 * Every sentence that reaches the page was written by the applicant and is in
 * {@link ResumeModel}; this class decides which summary paragraph opens, which
 * project leads, and which project is cut to keep the document to one page. It
 * cannot produce a claim that is not already approved, because it has no way to
 * write a sentence.
 *
 * <p>The alternative - handing the posting and the resume to a language model and
 * asking for a tailored version - produces better prose and is not worth it. It
 * quietly promotes "integrated ElevenLabs TTS" into "led speech infrastructure",
 * and the applicant then has to defend that sentence in an interview, against a
 * work history that may be checked.
 *
 * <p>Matching is on tags, not on free-text similarity. A tag is a word the
 * applicant has attached to his own work; scoring against those keeps the whole
 * process explainable in the digest - "matched kafka, spring boot, postgres" is a
 * sentence a human can check.
 */
@Component
public class ResumeTailor {

    /**
     * A tag in the title is worth more than the same tag in the description.
     *
     * <p>"Java" in "Java Backend Engineer" is the job; "Java" in a paragraph
     * listing eight languages the team has touched is trivia. Without the
     * weighting, a description that name-checks every technology once ranks all
     * projects identically, and the ordering becomes whatever the YAML happens to
     * say.
     */
    private static final int TITLE_WEIGHT = 3;
    private static final int DESCRIPTION_WEIGHT = 1;

    private final ResumeModel resume;

    public ResumeTailor(ResumeModel resume) {
        this.resume = resume;
    }

    public TailoredResume tailor(Posting posting) {
        String title = lower(posting.getTitle());
        String description = lower(posting.getDescriptionText());

        List<String> matched = new ArrayList<>();
        for (String tag : resume.allTags()) {
            if (mentions(title, tag) || mentions(description, tag)) {
                matched.add(tag);
            }
        }

        ResumeModel.Summary summary = bestSummary(title, description);

        // Projects, best match first. A stable sort on a zero score leaves the
        // YAML order intact, which is the applicant's own preference order and a
        // better default than anything computed.
        List<ScoredProject> scored = new ArrayList<>();
        for (ResumeModel.Project project : resume.projects()) {
            scored.add(new ScoredProject(project, score(project, title, description)));
        }
        scored.sort(Comparator.comparingInt(ScoredProject::score).reversed());

        List<ResumeModel.Project> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (ScoredProject entry : scored) {
            if (kept.size() < resume.maxProjects()) {
                ResumeModel.Project project = entry.project();
                kept.add(new ResumeModel.Project(
                        project.name(), project.stack(), project.tags(),
                        bestBullets(project.bullets(), title, description,
                                resume.maxBulletsPerProject())));
            } else {
                dropped.add(entry.project().name());
            }
        }

        List<ResumeModel.Job> jobs = new ArrayList<>();
        for (ResumeModel.Job job : resume.experience()) {
            jobs.add(new ResumeModel.Job(
                    job.company(), job.title(), job.location(), job.period(), job.note(),
                    bestBullets(job.bullets(), title, description,
                            resume.maxBulletsPerJob())));
        }

        return new TailoredResume(
                summary,
                resume.skills(),
                jobs,
                kept,
                resume.education(),
                resume.extras(),
                List.copyOf(new LinkedHashSet<>(matched)),
                dropped);
    }

    /**
     * The summary paragraph whose tags the posting mentions most.
     *
     * <p>Ties fall to the first one in the YAML, which is why the general-purpose
     * paragraph is written first: an unmatched posting gets the safe opening
     * rather than an arbitrary specialist one.
     */
    private ResumeModel.Summary bestSummary(String title, String description) {
        ResumeModel.Summary best = null;
        int bestScore = -1;
        for (ResumeModel.Summary summary : resume.summaries()) {
            int score = 0;
            for (String tag : summary.tags() == null ? List.<String>of() : summary.tags()) {
                if (mentions(title, tag)) {
                    score += TITLE_WEIGHT;
                } else if (mentions(description, tag)) {
                    score += DESCRIPTION_WEIGHT;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = summary;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "No summaries configured - job-radar.resume.summaries must have at least one");
        }
        return best;
    }

    /**
     * The {@code limit} most relevant bullets, in the order they were written.
     *
     * <p>Two orderings are involved and confusing them looks like a bug. Selection
     * is by relevance; presentation is by the applicant's own order. A bullet list
     * re-sorted by score reads as though it were assembled by a machine, because
     * the narrative order - what was built, then what it achieved - is destroyed.
     * So the top {@code limit} are chosen and then put back in sequence.
     */
    private List<ResumeModel.Bullet> bestBullets(
            List<ResumeModel.Bullet> bullets, String title, String description, int limit) {

        if (bullets == null || bullets.size() <= limit) {
            return bullets;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < bullets.size(); i++) {
            order.add(i);
        }
        // Ties fall to the earlier bullet, so an unmatched posting keeps the
        // opening bullets rather than an arbitrary subset.
        List<ResumeModel.Bullet> source = bullets;
        order.sort(Comparator.<Integer>comparingInt(
                        i -> scoreTags(source.get(i).tags(), title, description))
                .reversed()
                .thenComparingInt(i -> i));

        List<Integer> chosen = new ArrayList<>(order.subList(0, limit));
        chosen.sort(Comparator.naturalOrder());

        List<ResumeModel.Bullet> kept = new ArrayList<>(limit);
        chosen.forEach(i -> kept.add(source.get(i)));
        return kept;
    }

    private int scoreTags(Iterable<String> tags, String title, String description) {
        int score = 0;
        for (String tag : tags) {
            if (mentions(title, tag)) {
                score += TITLE_WEIGHT;
            } else if (mentions(description, tag)) {
                score += DESCRIPTION_WEIGHT;
            }
        }
        return score;
    }

    private int score(ResumeModel.Project project, String title, String description) {
        Set<String> tags = new LinkedHashSet<>(project.tags());
        project.bullets().forEach(bullet -> tags.addAll(bullet.tags()));
        return scoreTags(tags, title, description);
    }

    /**
     * Whole-word containment.
     *
     * <p>Substring matching would score "go" against "Django", "algorithm" and
     * "category" - which is the same class of bug as the "distributed" match in
     * the geography filter, and it lands here as a project order nobody can
     * explain rather than as a visible wrong answer.
     */
    static boolean mentions(String haystack, String tag) {
        if (haystack.isEmpty() || tag == null || tag.isBlank()) {
            return false;
        }
        String needle = tag.toLowerCase(Locale.ROOT).trim();
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !isWordCharAt(haystack, at - 1);
            int end = at + needle.length();
            boolean endOk = end == haystack.length() || !isWordCharAt(haystack, end);
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * Whether the character at {@code index} continues a technology name.
     *
     * <p>'+' and '#' always count, so "c" does not match inside "c++" or "c#".
     *
     * <p>'.' counts only when a letter or digit follows it, which is the whole
     * reason this is a positional test rather than a test on the character. A
     * naive "'.' is part of a word" rule keeps "node.js" together but also makes
     * a full stop a word character - so "experience with Spring Boot." stops
     * matching the tag "spring boot", and every tag at the end of a sentence
     * silently scores zero. That is a plain-wrong project ordering with nothing
     * on screen to say so.
     */
    private static boolean isWordCharAt(String text, int index) {
        char c = text.charAt(index);
        if (Character.isLetterOrDigit(c) || c == '+' || c == '#') {
            return true;
        }
        return c == '.'
                && index + 1 < text.length()
                && Character.isLetterOrDigit(text.charAt(index + 1));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record ScoredProject(ResumeModel.Project project, int score) {
    }
}
