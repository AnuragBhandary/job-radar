package com.anuragbhandary.jobradar.evidence;

import static com.anuragbhandary.jobradar.evidence.EvidenceProblem.error;
import static com.anuragbhandary.jobradar.evidence.EvidenceProblem.warning;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether the bank and the resume say the same things.
 *
 * <p>For as long as both exist, the resume's bullets must be a subset of what the
 * bank approves: every bullet under a job or project is the claim, or an approved
 * variant, of an evidence item from the matching source. The bank may hold more
 * than the resume; the resume may not hold anything the bank does not.
 *
 * <p>When they disagree the planner does not guess which one is right. It tailors
 * the resume exactly as it did before the bank existed and says why, because a
 * resume planned from a bank that has drifted would silently drop the bullet nobody
 * copied across.
 */
public record ResumeConsistency(List<EvidenceProblem> problems) {

    public ResumeConsistency {
        problems = List.copyOf(problems);
    }

    public boolean consistent() {
        return problems.stream().noneMatch(EvidenceProblem::isError);
    }

    public List<EvidenceProblem> errors() {
        return problems.stream().filter(EvidenceProblem::isError).toList();
    }

    public static ResumeConsistency check(EvidenceBank bank, ResumeModel resume) {
        List<EvidenceProblem> problems = new ArrayList<>();
        if (resume == null) {
            return new ResumeConsistency(problems);
        }
        Set<String> used = new HashSet<>();

        for (ResumeModel.Job job : orEmpty(resume.experience())) {
            List<ResumeModel.Bullet> bullets = orEmpty(job.bullets());
            Optional<EvidenceSource> source =
                    bank.sourceNamed(job.company(), EvidenceSource.Kind.EMPLOYMENT);
            if (source.isEmpty()) {
                if (!bullets.isEmpty()) {
                    problems.add(error("job '" + job.company() + "'", "the resume lists this job and "
                            + "the bank has no employment source named '" + job.company() + "'"));
                }
                continue;
            }
            used.add(source.get().id());
            StringBuilder text = new StringBuilder();
            List<String> tags = new ArrayList<>();
            for (ResumeModel.Bullet bullet : bullets) {
                if (bullet != null) {
                    text.append(bullet.text()).append('\n');
                    tags.addAll(bullet.tags());
                }
            }
            stack(source.get(), text.toString(), tags, problems);
            bullets(bank, source.get(), bullets, problems);
        }

        for (ResumeModel.Project project : resume.projects()) {
            List<ResumeModel.Bullet> bullets = orEmpty(project.bullets());
            Optional<EvidenceSource> source =
                    bank.sourceNamed(project.name(), EvidenceSource.Kind.PROJECT);
            if (source.isEmpty()) {
                if (!bullets.isEmpty()) {
                    problems.add(error("project '" + project.name() + "'", "the resume lists this "
                            + "project and the bank has no project source named '" + project.name() + "'"));
                }
                continue;
            }
            used.add(source.get().id());
            StringBuilder text = new StringBuilder(project.stack() == null ? "" : project.stack());
            List<String> tags = new ArrayList<>(project.tags());
            for (ResumeModel.Bullet bullet : bullets) {
                if (bullet != null) {
                    text.append('\n').append(bullet.text());
                    tags.addAll(bullet.tags());
                }
            }
            stack(source.get(), text.toString(), tags, problems);
            bullets(bank, source.get(), bullets, problems);
        }

        for (EvidenceSource source : bank.sources()) {
            if (!used.contains(source.id())) {
                problems.add(warning(source.id(), "'" + source.name()
                        + "' is not on the resume, so nothing from it is printed"));
            }
        }
        return new ResumeConsistency(problems);
    }

    /** A source's stack is a claim about the whole job or project, so the resume must back it. */
    private static void stack(EvidenceSource source, String text, Collection<String> tags,
            List<EvidenceProblem> problems) {
        Set<String> tagKeys = tags.stream().map(EvidenceItem::key).collect(Collectors.toSet());
        Set<String> named = EvidenceText.technologiesIn(text);
        for (String entry : source.stack()) {
            String key = EvidenceItem.key(entry);
            if (!tagKeys.contains(key) && !named.contains(key) && !EvidenceText.wholeWord(text, entry)) {
                problems.add(error(source.id(), "stack entry '" + entry + "' is not named or tagged "
                        + "anywhere under '" + source.name() + "' on the resume"));
            }
        }
    }

    private static void bullets(EvidenceBank bank, EvidenceSource source,
            List<ResumeModel.Bullet> bullets, List<EvidenceProblem> problems) {
        List<EvidenceItem> items = bank.itemsFrom(source.id());
        for (ResumeModel.Bullet bullet : bullets) {
            if (bullet == null || bullet.text() == null) {
                continue;
            }
            String wanted = EvidenceText.normalise(bullet.text());
            boolean approved = items.stream().anyMatch(item -> item.approvedTexts().stream()
                    .anyMatch(t -> EvidenceText.normalise(t).equals(wanted)));
            if (!approved) {
                problems.add(error(source.id(), "the resume says \"" + shorten(bullet.text())
                        + "\" and no usable evidence item of this source approves that sentence"));
            }
        }
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 90 ? flat : flat.substring(0, 89) + "…";
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
