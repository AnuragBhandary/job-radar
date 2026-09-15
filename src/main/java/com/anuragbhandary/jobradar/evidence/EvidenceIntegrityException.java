package com.anuragbhandary.jobradar.evidence;

import java.util.List;

/**
 * Application material cannot be generated safely from the evidence there is.
 *
 * <p>Thrown instead of falling back. There is no second copy of the applicant's
 * career claims to fall back to, and inventing a resume, a letter or an answer
 * from whatever text is lying around is exactly what the evidence bank exists to
 * prevent. "Cannot safely generate" is an acceptable outcome; a stale or invented
 * claim on a real application is not.
 */
public class EvidenceIntegrityException extends RuntimeException {

    private final List<String> reasons;

    public EvidenceIntegrityException(String headline, List<String> reasons) {
        super(headline + (reasons.isEmpty() ? "" : ": " + String.join("; ", reasons)));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
