package com.anuragbhandary.jobradar.knowledge;

/**
 * Whether the applicant has said this may be used.
 *
 * <p>Separate from {@link KnowledgeSource} because the two answer different
 * questions and the old system conflated them: writing an answer to
 * {@code applicant.yml} was simultaneously the act of approving it and the act
 * of forgetting where it came from.
 */
public enum ApprovalState {

    /**
     * Stored, resolvable, and not usable on its own where approval is required.
     * Every AI proposal starts here.
     */
    UNAPPROVED,

    /** He read it and said yes, for the scope recorded beside it. */
    APPROVED,

    /**
     * He read it and said no.
     *
     * <p>Kept rather than deleted so the same draft is not offered again, and so
     * "why is this still unanswered?" has an answer.
     */
    REJECTED;

    public boolean isApproved() {
        return this == APPROVED;
    }
}
