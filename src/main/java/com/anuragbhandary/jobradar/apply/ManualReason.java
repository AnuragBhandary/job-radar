package com.anuragbhandary.jobradar.apply;

/**
 * Why an application needs a person rather than a browser.
 *
 * <p>Structured, because "manual" covers several situations that look identical
 * in a status column and are not remotely the same job. Before this, all of them
 * were {@code NEEDS_HUMAN} with a sentence, and the worklist told a captcha from
 * a missing form by testing whether the sentence began with "No form found".
 *
 * @param valuesReusable whether the answers Job Radar worked out are still worth
 *                       having. Almost always yes: a captcha does not invalidate
 *                       a correctly resolved address, and a person finishing the
 *                       form by hand wants the list.
 * @param resumable      whether trying again could work without anything
 *                       changing. A captcha might; an unsupported site will not.
 */
public enum ManualReason {

    /**
     * The board never showed a form.
     *
     * <p>A sign-in wall, or an apply link that leads somewhere else. The tailored
     * resume is already rendered, so this is a five-minute job by hand.
     */
    NO_FORM("The board never showed a form",
            "the apply link may need a sign-in, or lead somewhere else", true, false),

    /**
     * A captcha is on the page.
     *
     * <p>Solving one is out of scope permanently. It is the board saying it does
     * not want automated submissions, and the honest response is to hand the
     * browser back.
     */
    CAPTCHA("This form has a captcha",
            "the board is asking for a person, and gets one", true, true),

    /** A widget nothing here knows how to drive. */
    UNSUPPORTED_CONTROL("A control on this form cannot be filled automatically",
            "the answer is known; the widget is not one this tool can drive", true, true),

    /** The whole flow is one the tool cannot follow. */
    UNSUPPORTED_SITE("This application flow is not supported",
            "the site does not work the way the form reader expects", true, false),

    /** The board wants an account before it will show the form. */
    LOGIN_REQUIRED("This board wants you signed in",
            "sign in once with `login --url=...` and the session is kept", true, true),

    /**
     * The browser or the network failed in a way that is nobody's fault.
     *
     * <p>Separate from {@link com.anuragbhandary.jobradar.apply.AttemptStatus#FAILED}:
     * this one still has a filled form and usable answers behind it.
     */
    AUTOMATION_ERROR("The browser failed part-way through",
            "worth retrying; the answers already worked out are kept", true, true);

    private final String explanation;
    private final String detail;
    private final boolean valuesReusable;
    private final boolean resumable;

    ManualReason(String explanation, String detail, boolean valuesReusable, boolean resumable) {
        this.explanation = explanation;
        this.detail = detail;
        this.valuesReusable = valuesReusable;
        this.resumable = resumable;
    }

    /** One line for a person. */
    public String explanation() {
        return explanation;
    }

    /** The rest of the sentence, for a screen with room for it. */
    public String detail() {
        return detail;
    }

    public boolean valuesReusable() {
        return valuesReusable;
    }

    public boolean resumable() {
        return resumable;
    }
}
