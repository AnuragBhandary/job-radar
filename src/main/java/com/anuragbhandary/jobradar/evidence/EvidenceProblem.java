package com.anuragbhandary.jobradar.evidence;

/**
 * Something wrong with the evidence file, or with joining it to the profile.
 *
 * <p>An ERROR removes what it names - an item, or one variant of an item - so nothing
 * broken can reach application material. A WARNING leaves it in and says what looks
 * wrong.
 *
 * @param where       the item id, "item/variant", a source id, "file", or the
 *                    applicant.yml entry concerned
 * @param variantOnly true when the error is confined to one approved wording. Such
 *                    an error drops that wording and does not stop generation; every
 *                    other error does (see {@link EvidenceReadiness}).
 */
public record EvidenceProblem(Severity severity, String where, String message, boolean variantOnly) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public EvidenceProblem(Severity severity, String where, String message) {
        this(severity, where, message, false);
    }

    public static EvidenceProblem error(String where, String message) {
        return new EvidenceProblem(Severity.ERROR, where, message);
    }

    public static EvidenceProblem warning(String where, String message) {
        return new EvidenceProblem(Severity.WARNING, where, message);
    }

    public static EvidenceProblem variantError(String where, String message) {
        return new EvidenceProblem(Severity.ERROR, where, message, true);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** Whether this stops application material being generated. */
    public boolean blocksGeneration() {
        return isError() && !variantOnly;
    }

    @Override
    public String toString() {
        return severity + " " + where + ": " + message;
    }
}
