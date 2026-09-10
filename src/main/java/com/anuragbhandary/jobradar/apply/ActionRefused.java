package com.anuragbhandary.jobradar.apply;

/**
 * An action the preparation screen asked for that must not happen.
 *
 * <p>Carries two sentences because a refusal with one is not usable. "Stale
 * action" says nothing; "this draft was replaced while the page was open" plus
 * "reload and read the new one" is something a person can act on. The old system
 * had a single failure mode - a stack trace in a browser tab - and that is the
 * whole reason this exists.
 */
public class ActionRefused extends RuntimeException {

    private final String code;
    private final String what;
    private final String remedy;

    /**
     * @param code   short and stable, for a client to switch on. Never shown as-is.
     * @param what   what happened, in one sentence
     * @param remedy what to do about it
     */
    public ActionRefused(String code, String what, String remedy) {
        super(what + " " + remedy);
        this.code = code;
        this.what = what;
        this.remedy = remedy;
    }

    /** The page has moved on since it was rendered. */
    public static ActionRefused stale(String what) {
        return new ActionRefused("stale", what,
                "Reload the page and look at what it says now.");
    }

    /** The action does not apply to a field in this state. */
    public static ActionRefused wrongState(String what) {
        return new ActionRefused("wrong-state", what, "Reload the page.");
    }

    public static ActionRefused notFound(String what) {
        return new ActionRefused("not-found", what, "Go back to the application.");
    }

    /** Something the knowledge rules forbid - most often an unsafe scope. */
    public static ActionRefused unsafe(String what) {
        return new ActionRefused("unsafe", what, "Choose a narrower scope.");
    }

    /** Something outside this application failed: a model, a browser, a board. */
    public static ActionRefused unavailable(String what, String remedy) {
        return new ActionRefused("unavailable", what, remedy);
    }

    public static ActionRefused invalid(String what, String remedy) {
        return new ActionRefused("invalid", what, remedy);
    }

    public String code() {
        return code;
    }

    public String what() {
        return what;
    }

    public String remedy() {
        return remedy;
    }
}
