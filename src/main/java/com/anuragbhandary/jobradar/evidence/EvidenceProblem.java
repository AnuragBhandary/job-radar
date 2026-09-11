package com.anuragbhandary.jobradar.evidence;

/**
 * Something wrong with the evidence file.
 *
 * <p>An ERROR removes what it names from the bank - an item, or one variant of an
 * item - so nothing broken can reach a resume. A WARNING leaves it in and says what
 * looks wrong.
 *
 * @param where the item id, "item/variant", a source id, or "file"
 */
public record EvidenceProblem(Severity severity, String where, String message) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public static EvidenceProblem error(String where, String message) {
        return new EvidenceProblem(Severity.ERROR, where, message);
    }

    public static EvidenceProblem warning(String where, String message) {
        return new EvidenceProblem(Severity.WARNING, where, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " " + where + ": " + message;
    }
}
