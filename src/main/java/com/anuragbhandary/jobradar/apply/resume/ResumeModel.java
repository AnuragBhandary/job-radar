package com.anuragbhandary.jobradar.apply.resume;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The resume as data rather than as a PDF.
 *
 * <p>Everything downstream depends on this being the only source of claims. The
 * tailor reorders these items and the renderer prints them; neither invents a
 * sentence, and the language model that writes the cover letter is given this
 * object and told it may use nothing else.
 *
 * <p>That constraint is not stylistic. Any resume may be checked, and a resume
 * containing an embellishment its owner did not write turns a verification
 * conversation that is merely awkward into one that ends an offer. Every bullet
 * in the YAML is something its author can defend in an interview, and nothing
 * else ever reaches the page.
 *
 * @param tagWeights how much a tag match on a bullet is worth when ordering.
 *                   Tuning lives in config because the right weighting is found
 *                   by looking at rendered output, not by reasoning about it.
 */
@ConfigurationProperties(prefix = "job-radar.resume")
public record ResumeModel(
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

    /**
     * One opening paragraph, and the tags that make it the right one.
     *
     * <p>Several are kept rather than one being rewritten per posting, because a
     * summary is the part of a resume most likely to drift into a claim that is
     * not quite true. Choosing between paragraphs the applicant has already
     * approved cannot produce a sentence he has not read.
     */
    public record Summary(String id, List<String> tags, String text) {
    }

    public record SkillGroup(String group, List<String> items) {
    }

    /**
     * @param id optional, and only ever read by the resume analysis. A stable name
     *           for this bullet that survives rewording, for when a rewrite has to
     *           say which bullet it came from. Left out, one is derived from the
     *           bullet's parent and text - see
     *           {@link com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources}.
     *           Nothing rendered depends on it.
     */
    public record Bullet(String text, List<String> tags, String id) {

        /**
         * The binding constructor, named because the two-argument one below would
         * otherwise leave Spring guessing which to bind applicant.yml through.
         */
        @ConstructorBinding
        public Bullet {
        }

        public Bullet(String text, List<String> tags) {
            this(text, tags, null);
        }

        public List<String> tags() {
            return tags == null ? List.of() : tags;
        }
    }

    /**
     * @param note an unusual arrangement, stated plainly rather than smoothed
     *             over. The honest version reads better than a title that hides
     *             it, and it matters when the alternative is a background check
     *             finding it later.
     */
    public record Job(
            String company,
            String title,
            String location,
            String period,
            String note,
            List<Bullet> bullets) {
    }

    public record Project(
            String name,
            String stack,
            List<String> tags,
            List<Bullet> bullets) {

        public List<String> tags() {
            return tags == null ? List.of() : tags;
        }
    }

    public record Education(
            String degree,
            String institution,
            String location,
            String period,
            String detail) {
    }

    public List<Summary> summaries() {
        return summaries == null ? List.of() : summaries;
    }

    public List<Project> projects() {
        return projects == null ? List.of() : projects;
    }

    public List<String> extras() {
        return extras == null ? List.of() : extras;
    }

    /** Every tag used anywhere, for checking the posting vocabulary against. */
    public List<String> allTags() {
        List<String> tags = new ArrayList<>();
        for (Project project : projects()) {
            tags.addAll(project.tags());
            project.bullets().forEach(b -> tags.addAll(b.tags()));
        }
        if (experience != null) {
            experience.forEach(job -> job.bullets().forEach(b -> tags.addAll(b.tags())));
        }
        summaries().forEach(s -> tags.addAll(s.tags() == null ? List.of() : s.tags()));
        return tags.stream().distinct().toList();
    }

    public int maxProjects() {
        // One page is the target. Two projects plus the job is what fits once the
        // bullets are capped as well - three projects at full length came out at
        // three pages, which is a resume nobody finishes reading.
        return maxProjects <= 0 ? 2 : maxProjects;
    }

    /**
     * How many bullets survive per role.
     *
     * <p>Capping these is where tailoring stops being cosmetic. Five bullets about
     * a job, three of which are irrelevant to the posting, reads as a list of
     * everything the applicant has ever done; the same job with its three most
     * relevant bullets reads as someone who has done this before. The dropped ones
     * are still true - they are just not the argument being made here.
     */
    public int maxBulletsPerJob() {
        return maxBulletsPerJob <= 0 ? 4 : maxBulletsPerJob;
    }

    public int maxBulletsPerProject() {
        return maxBulletsPerProject <= 0 ? 3 : maxBulletsPerProject;
    }
}
