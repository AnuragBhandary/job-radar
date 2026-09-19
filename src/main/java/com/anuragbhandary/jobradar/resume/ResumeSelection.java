package com.anuragbhandary.jobradar.resume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which bullets to print, and in what order. Selection and ordering only.
 *
 * <p>Bullets are referred to by position, as {@code resume --list} prints them:
 * {@code e1.3} is the third bullet of the first job, {@code p2.1} the first bullet
 * of the second project. In a pick list:
 * <ul>
 *   <li>a job with picked bullets prints exactly those, in the order given; a job
 *       with none picked prints its first {@code max-bullets-per-job};</li>
 *   <li>if any project bullet is picked, only picked projects print, in the order
 *       their first bullet appears; otherwise the first {@code max-projects} print,
 *       each with its first {@code max-bullets-per-project}.</li>
 * </ul>
 * An unknown reference is an error, never skipped: a resume missing a line
 * someone asked for looks finished and is not.
 *
 * @param summaryId the summary paragraph to use; null means the first
 * @param picks     bullet references in print order
 */
public record ResumeSelection(String summaryId, List<String> picks) {

    private static final Pattern REF = Pattern.compile("([ep])(\\d+)\\.(\\d+)");

    /** The resume with this selection applied. */
    public Selected apply(ResumeSource source) {
        ResumeSource.Summary summary = summary(source);

        Map<Integer, List<ResumeSource.Bullet>> jobPicks = new LinkedHashMap<>();
        Map<Integer, List<ResumeSource.Bullet>> projectPicks = new LinkedHashMap<>();
        for (String raw : picks == null ? List.<String>of() : picks) {
            String ref = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (ref.isEmpty()) {
                continue;
            }
            Matcher m = REF.matcher(ref);
            if (!m.matches()) {
                throw new IllegalArgumentException("Not a bullet reference: " + raw
                        + " (expected e<job>.<bullet> or p<project>.<bullet>)");
            }
            int item = Integer.parseInt(m.group(2));
            int bullet = Integer.parseInt(m.group(3));
            if (m.group(1).equals("e")) {
                ResumeSource.Job job = at(source.experience(), item, raw);
                jobPicks.computeIfAbsent(item, k -> new ArrayList<>())
                        .add(at(job.bullets(), bullet, raw));
            } else {
                ResumeSource.Project project = at(source.projects(), item, raw);
                projectPicks.computeIfAbsent(item, k -> new ArrayList<>())
                        .add(at(project.bullets(), bullet, raw));
            }
        }

        List<ResumeSource.Job> jobs = new ArrayList<>();
        for (int i = 0; i < source.experience().size(); i++) {
            ResumeSource.Job job = source.experience().get(i);
            List<ResumeSource.Bullet> bullets = jobPicks.getOrDefault(i + 1,
                    first(job.bullets(), source.maxBulletsPerJob()));
            jobs.add(new ResumeSource.Job(job.company(), job.title(), job.location(),
                    job.period(), job.note(), bullets));
        }

        List<ResumeSource.Project> projects = new ArrayList<>();
        if (projectPicks.isEmpty()) {
            for (ResumeSource.Project project : first(source.projects(), source.maxProjects())) {
                projects.add(withBullets(project,
                        first(project.bullets(), source.maxBulletsPerProject())));
            }
        } else {
            projectPicks.forEach((index, bullets) ->
                    projects.add(withBullets(source.projects().get(index - 1), bullets)));
        }

        return new Selected(source.headline(), summary, source.skills(), jobs, projects,
                source.education(), source.extras());
    }

    /** What the renderer prints. */
    public record Selected(String headline, ResumeSource.Summary summary,
            List<ResumeSource.SkillGroup> skills, List<ResumeSource.Job> experience,
            List<ResumeSource.Project> projects, List<ResumeSource.Education> education,
            List<String> extras) {
    }

    private ResumeSource.Summary summary(ResumeSource source) {
        if (source.summaries().isEmpty()) {
            throw new IllegalArgumentException("applicant.yml has no resume summaries");
        }
        if (summaryId == null || summaryId.isBlank()) {
            return source.summaries().getFirst();
        }
        return source.summaries().stream()
                .filter(s -> summaryId.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No summary with id '"
                        + summaryId + "'. Run resume --list for the ids."));
    }

    private static ResumeSource.Project withBullets(ResumeSource.Project project,
            List<ResumeSource.Bullet> bullets) {
        return new ResumeSource.Project(project.name(), project.stack(), project.tags(), bullets);
    }

    private static <T> T at(List<T> list, int oneBased, String ref) {
        if (oneBased < 1 || oneBased > list.size()) {
            throw new IllegalArgumentException("No such item: " + ref);
        }
        return list.get(oneBased - 1);
    }

    private static <T> List<T> first(List<T> list, int max) {
        return max > 0 && list.size() > max ? list.subList(0, max) : list;
    }
}
