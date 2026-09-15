package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.ComposedResume;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Whether application material may be generated at all. The fail-closed gate.
 *
 * <p>Two kinds of trouble are kept apart. <b>Configuration</b> - the tailoring
 * switch, a missing model - changes <em>how</em> material is produced and is
 * handled where it applies. <b>Evidence integrity</b> is this class: a missing or
 * refused bank file, an item that failed validation, bullets still sitting in
 * applicant.yml, a job with no evidence source. Any of those and the resume, the
 * cover letter and career answers are all refused, with the reasons, rather than
 * produced from a partial or stale set of claims.
 *
 * <p>A variant that failed validation does not block: it only removes that one
 * wording, and the claim it was a wording of still stands.
 */
@Component
public class EvidenceReadiness {

    private final EvidenceBank bank;
    private final ComposedResume composed;

    public EvidenceReadiness(EvidenceBank bank, ComposedResume composed) {
        this.bank = bank;
        this.composed = composed;
    }

    /** Every reason generation is refused; empty when it is not. */
    public List<String> blockers() {
        List<String> reasons = new ArrayList<>();
        if (bank.isEmpty()) {
            String why = bank.problems().stream().map(EvidenceProblem::message).findFirst()
                    .orElse("it holds no items");
            reasons.add("the evidence bank at " + bank.origin() + " is unavailable: " + why);
        }
        bank.problems().stream()
                .filter(EvidenceProblem::blocksGeneration)
                .filter(p -> !bank.isEmpty() || !"file".equals(p.where()))
                .forEach(p -> reasons.add(p.toString()));
        composed.blocking().forEach(p -> reasons.add(p.toString()));
        return List.copyOf(reasons);
    }

    public boolean ready() {
        return blockers().isEmpty();
    }

    public void requireReady() {
        List<String> reasons = blockers();
        if (!reasons.isEmpty()) {
            throw new EvidenceIntegrityException("Evidence Bank unavailable - nothing was generated",
                    reasons);
        }
    }
}
