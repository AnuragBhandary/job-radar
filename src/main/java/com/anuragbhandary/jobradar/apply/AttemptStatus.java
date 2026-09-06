package com.anuragbhandary.jobradar.apply;

/** How far one application got. */
public enum AttemptStatus {
    /**
     * Documents rendered, form filled, nothing sent. The normal resting state:
     * the tool stops here and a human decides.
     */
    PREPARED,
    /**
     * Filled, but cannot be submitted as it stands - a required field with no
     * configured answer, or a captcha. Names what is missing so the profile can
     * learn it.
     */
    NEEDS_HUMAN,
    /** Sent. The only status that is irreversible, and the only one that writes to the tracker. */
    SUBMITTED,
    /** The attempt threw before it finished. The reason is on the record. */
    FAILED,
    /** Deliberately passed over, with a reason - e.g. already applied to this company. */
    SKIPPED
}
