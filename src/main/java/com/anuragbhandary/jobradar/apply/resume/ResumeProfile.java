package com.anuragbhandary.jobradar.apply.resume;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The resume's presentation, as applicant.yml writes it - and nothing it claims.
 *
 * <h2>Who owns what</h2>
 * <b>This profile</b> owns how the resume is presented and the facts about the
 * applicant that are not career evidence: the headline, the approved summary
 * paragraphs, the skills list as he wants it printed, each job's company, title,
 * location, dates and arrangement note, education, the extras line, and the caps
 * that keep the page to one.
 *
 * <p><b>The evidence bank</b> ({@code ~/.config/job-radar/evidence.yml}) owns what
 * he did: every bullet under a job, every project, each project's stack line, the
 * technologies, metrics, qualifiers and approved wordings. {@link ResumeComposer}
 * joins the two by company name for jobs; projects come from the bank alone.
 *
 * <p>So a job here has no bullets and there is no project list here. Both keys are
 * still bound - {@link Employment#bullets} and {@link #projects} - for one reason:
 * a file that still carries them is reported, loudly, rather than silently
 * ignored. A stale copy of a claim nobody updates is the failure this split exists
 * to prevent. {@code evidence --migrate-profile} removes them once the bank is
 * confirmed to hold every one.
 */
@ConfigurationProperties(prefix = "job-radar.resume")
public record ResumeProfile(
        String headline,
        List<ResumeModel.Summary> summaries,
        List<ResumeModel.SkillGroup> skills,
        List<Employment> experience,
        List<LegacyProject> projects,
        List<ResumeModel.Education> education,
        List<String> extras,
        int maxProjects,
        int maxBulletsPerJob,
        int maxBulletsPerProject) {

    /**
     * A job's metadata. Joined to the bank's employment source of the same name.
     *
     * @param bullets legacy. Must be empty; any bullet here is reported and never used
     */
    public record Employment(
            String company,
            String title,
            String location,
            String period,
            String note,
            List<ResumeModel.Bullet> bullets) {

        public List<ResumeModel.Bullet> bullets() {
            return bullets == null ? List.of() : bullets;
        }
    }

    /** Legacy. Projects live in the evidence bank; an entry here is reported and never used. */
    public record LegacyProject(String name, String stack, List<String> tags,
            List<ResumeModel.Bullet> bullets) {
    }

    public static ResumeProfile empty() {
        return new ResumeProfile(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 0, 0, 0);
    }

    public List<ResumeModel.Summary> summaries() {
        return summaries == null ? List.of() : summaries;
    }

    public List<ResumeModel.SkillGroup> skills() {
        return skills == null ? List.of() : skills;
    }

    public List<Employment> experience() {
        return experience == null ? List.of() : experience;
    }

    public List<LegacyProject> projects() {
        return projects == null ? List.of() : projects;
    }

    public List<ResumeModel.Education> education() {
        return education == null ? List.of() : education;
    }

    public List<String> extras() {
        return extras == null ? List.of() : extras;
    }
}
