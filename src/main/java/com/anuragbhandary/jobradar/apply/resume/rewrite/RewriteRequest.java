package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.List;

/**
 * One rewrite job: one source bullet, what the posting wants from it, and what it
 * may not say.
 *
 * <p>There is no field for an employer, a title, a date or a project name. A
 * rewrite cannot change those because it is never given them as anything it
 * could write back. There is no summary variant either: summaries are chosen, not
 * written - see {@link RewritePlanner}.
 *
 * @param targets    requirements this bullet's primary evidence supports,
 *                   strongest first
 * @param adjacent   requirements he does not have but this bullet is related to -
 *                   the evidence may be emphasised, the requirement never named
 * @param prohibited requirement terms that must not appear unless the source
 *                   already says them
 * @param maxChars   a length the prompt asks for; exceeding it is a warning, not a
 *                   failure, because page length is measured separately
 */
public record RewriteRequest(
        String sourceId,
        String originalText,
        EvidenceScope scope,
        List<Target> targets,
        List<Adjacent> adjacent,
        List<String> prohibited,
        int maxChars) {

    public record Target(String term, RequirementImportance importance, String quote) {
    }

    public record Adjacent(String term, ExperienceLevel level, List<String> via) {
    }
}
