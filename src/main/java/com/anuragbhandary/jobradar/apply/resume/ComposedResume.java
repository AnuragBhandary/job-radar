package com.anuragbhandary.jobradar.apply.resume;

import com.anuragbhandary.jobradar.evidence.EvidenceProblem;
import java.util.List;

/**
 * The runtime resume, and what was wrong while putting it together.
 *
 * @param resume   profile metadata plus evidence-bank claims. Every bullet in it
 *                 carries its evidence id.
 * @param problems anything that stops the two sources joining cleanly: bullets
 *                 still in applicant.yml, a job with no evidence source. A blocking
 *                 one stops application generation; see
 *                 {@link com.anuragbhandary.jobradar.evidence.EvidenceReadiness}.
 */
public record ComposedResume(ResumeModel resume, List<EvidenceProblem> problems) {

    public ComposedResume {
        problems = List.copyOf(problems);
    }

    public List<EvidenceProblem> blocking() {
        return problems.stream().filter(EvidenceProblem::blocksGeneration).toList();
    }
}
