package com.anuragbhandary.jobradar.apply.resume.analysis;

/**
 * How strongly a posting asks for something.
 *
 * <p>Declared strongest first, so ordinal order is priority order. The weights
 * are what let a required technology outrank a preferred one however often the
 * preferred one is mentioned - the thing the tag-count tailor cannot do, because
 * it counts mentions rather than reading what the posting says it needs.
 */
public enum RequirementImportance {

    /** Stated as required, minimum, must-have, or listed under requirements. */
    REQUIRED(3.0),

    /** Nice to have, a plus, a bonus, preferred. */
    PREFERRED(1.0),

    /**
     * Mentioned about the work - the stack, the responsibilities - without being
     * stated as a requirement. Worth something, and less than either of the above.
     */
    SIGNAL(0.5);

    private final double weight;

    RequirementImportance(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    /** The stronger of the two; ties keep this one. */
    public RequirementImportance strongerOf(RequirementImportance other) {
        return other == null || ordinal() <= other.ordinal() ? this : other;
    }
}
