package com.anuragbhandary.jobradar.resume;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The resume as {@code applicant.yml} writes it: every claim, in his own words.
 *
 * <p>Nothing in this package writes a claim. {@code resume} chooses which bullets
 * to print and in what order, and every bullet it prints is one of these, verbatim.
 * Personal data, so it is imported from {@code ~/.config/job-radar/applicant.yml}
 * and never lives in this repository.
 *
 * @param maxProjects          projects printed when none are picked
 * @param maxBulletsPerJob     bullets printed under a job whose bullets are not picked
 * @param maxBulletsPerProject bullets printed under a project whose bullets are not picked
 */
@ConfigurationProperties(prefix = "job-radar.resume")
public record ResumeSource(
        String headline,
        List<Summary> summaries,
        List<SkillGroup> skills,
        List<Job> experience,
        List<Project> projects,
        List<Education> education,
        List<String> extras,
        int maxProjects,
        int maxBulletsPerJob,
        int maxBulletsPerProject) {

    public record Summary(String id, List<String> tags, String text) {
    }

    public record SkillGroup(String group, List<String> items) {
    }

    public record Bullet(String text, List<String> tags, String id) {
    }

    /** @param note the arrangement, printed under the title, e.g. how it was paid */
    public record Job(String company, String title, String location, String period,
            String note, List<Bullet> bullets) {

        public List<Bullet> bullets() {
            return bullets == null ? List.of() : bullets;
        }
    }

    public record Project(String name, String stack, List<String> tags, List<Bullet> bullets) {

        public List<Bullet> bullets() {
            return bullets == null ? List.of() : bullets;
        }
    }

    public record Education(String degree, String institution, String location, String period,
            String detail) {
    }

    public List<Summary> summaries() {
        return summaries == null ? List.of() : summaries;
    }

    public List<SkillGroup> skills() {
        return skills == null ? List.of() : skills;
    }

    public List<Job> experience() {
        return experience == null ? List.of() : experience;
    }

    public List<Project> projects() {
        return projects == null ? List.of() : projects;
    }

    public List<Education> education() {
        return education == null ? List.of() : education;
    }

    public List<String> extras() {
        return extras == null ? List.of() : extras;
    }

    public boolean isEmpty() {
        return summaries().isEmpty() && experience().isEmpty() && projects().isEmpty();
    }
}
