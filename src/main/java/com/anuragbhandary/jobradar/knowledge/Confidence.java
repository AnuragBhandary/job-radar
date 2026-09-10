package com.anuragbhandary.jobradar.knowledge;

/**
 * How much the resolver trusts what it worked out.
 *
 * <p>Three values, not a float. A number between 0 and 1 invites arithmetic -
 * averaging two sources, decaying by age, multiplying by a weight - and none of
 * that arithmetic could be explained on a review screen. Three values can be
 * printed as a sentence, which is the only thing confidence is for here.
 */
public enum Confidence {

    /**
     * Auto-fillable. An explicit fact whose scope matches, or a derivation with
     * every input it needs.
     */
    HIGH,

    /**
     * Worth showing before it is used. A stale volatile fact, a derivation
     * missing a piece of context, or a historical answer.
     */
    MEDIUM,

    /**
     * Never auto-filled. Enough to make a proposal out of, not enough to send to
     * an employer unread.
     */
    LOW;

    public boolean atLeast(Confidence floor) {
        return ordinal() <= floor.ordinal();
    }

    /** One step down, for ageing a volatile fact or a thinly-evidenced derivation. */
    public Confidence downgrade() {
        return this == HIGH ? MEDIUM : LOW;
    }
}
