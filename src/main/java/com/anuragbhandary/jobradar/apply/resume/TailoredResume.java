package com.anuragbhandary.jobradar.apply.resume;

import java.util.List;

/**
 * One posting's view of the resume: the same facts, in a different order.
 *
 * @param matchedTags what the posting and the resume have in common. Printed in
 *                    the review file, because it is also the honest answer to
 *                    "why is this a good fit?" and worth reading before an
 *                    interview.
 * @param droppedProjects projects left off to keep the resume to one page, named
 *                        rather than silently discarded
 */
public record TailoredResume(
        ResumeModel.Summary summary,
        List<ResumeModel.SkillGroup> skills,
        List<ResumeModel.Job> experience,
        List<ResumeModel.Project> projects,
        List<ResumeModel.Education> education,
        List<String> extras,
        List<String> matchedTags,
        List<String> droppedProjects) {

    /** A one-line account of what was changed, for the review file and the log. */
    public String note() {
        StringBuilder sb = new StringBuilder("summary '" + summary.id() + "'");
        if (!matchedTags.isEmpty()) {
            sb.append("; matched ").append(String.join(", ", matchedTags));
        }
        if (!projects.isEmpty()) {
            sb.append("; led with ").append(projects.getFirst().name());
        }
        if (!droppedProjects.isEmpty()) {
            sb.append("; dropped ").append(String.join(", ", droppedProjects));
        }
        return sb.toString();
    }
}
