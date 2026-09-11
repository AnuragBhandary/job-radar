package com.anuragbhandary.jobradar.bench;

/**
 * One request to a local model, and what it cost.
 *
 * <p>Token counts and durations are whatever the server reported, or null when it
 * reported nothing. They are never estimated: a benchmark that fills a gap with a
 * guess is a benchmark whose numbers cannot be compared.
 *
 * @param wallMillis measured here, round trip, including any model load
 * @param evalNanos  time the server spent generating output tokens
 * @param loadNanos  time the server spent loading the model for this call
 * @param doneReason "stop", or "length" when the output cap cut it off
 */
public record ModelCall(
        boolean ok,
        String content,
        String error,
        long wallMillis,
        Integer promptTokens,
        Integer outputTokens,
        Long evalNanos,
        Long loadNanos,
        String doneReason) {

    public static ModelCall failed(String error, long wallMillis) {
        return new ModelCall(false, null, error, wallMillis, null, null, null, null, null);
    }

    public boolean truncated() {
        return "length".equals(doneReason);
    }
}
