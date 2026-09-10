package com.anuragbhandary.jobradar.knowledge.experience;

import java.util.ArrayList;
import java.util.List;

/**
 * What may honestly be said about one technology, decided before anything is
 * written.
 *
 * <p>The structure that makes the whole feature safe. A model is never asked
 * "does he know Kubernetes?" - that is settled here, in Java, from his resume -
 * and is only asked to phrase a conclusion this record already contains. The
 * separation is the point: knowledge is a fact about him, positioning is a fact
 * about how to say it, and a generated sentence changes neither.
 *
 * <p>So this is not stored on an {@link com.anuragbhandary.jobradar.knowledge.Assertion}
 * and never becomes one. Answering a Kubernetes question does not teach the
 * system that he has Kubernetes experience; the index still says he does not, and
 * the next form gets the same honest positioning rather than the last answer's
 * prose.
 *
 * @param subject     what was asked about, as the form wrote it
 * @param level       how close his evidence is
 * @param evidence    the entries an answer may name, strongest first. Empty at
 *                    {@link ExperienceLevel#NONE}.
 * @param via         the neighbouring technologies or ideas that produced an
 *                    ADJACENT or CONCEPTUAL verdict, for the explanation
 * @param mayClaim    sentences the answer is permitted to make
 * @param mustNotSay  the claims a validator will reject. Written down rather
 *                    than implied, because a prompt is a request and this is the
 *                    thing that is actually checked.
 */
public record Positioning(
        String subject,
        ExperienceLevel level,
        List<ExperienceIndex.Entry> evidence,
        List<String> via,
        List<String> mayClaim,
        List<String> mustNotSay,
        String framing) {

    public Positioning {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        via = via == null ? List.of() : List.copyOf(via);
        mayClaim = mayClaim == null ? List.of() : List.copyOf(mayClaim);
        mustNotSay = mustNotSay == null ? List.of() : List.copyOf(mustNotSay);
    }

    public boolean isDirect() {
        return level == ExperienceLevel.DIRECT;
    }

    /** True when the answer must open by saying he has not used the thing. */
    public boolean needsDisclaimer() {
        return level.requiresDisclaimer();
    }

    /** The technologies a generated answer may name. Nothing else is permitted. */
    public List<String> namedEvidence() {
        List<String> names = new ArrayList<>();
        evidence.forEach(entry -> names.add(entry.display()));
        return List.copyOf(names);
    }

    /**
     * The whole verdict in one sentence, for a screen and for the audit.
     *
     * <p>Reads as a finding rather than a score: "no direct Kubernetes
     * experience; related work in Docker, AWS" is something he can check against
     * his own resume, and "0.62" is not.
     */
    public String describe() {
        StringBuilder out = new StringBuilder(subject).append(": ")
                .append(level.summary());
        if (!evidence.isEmpty()) {
            out.append(" — ").append(String.join(", ", namedEvidence()));
        }
        if (!via.isEmpty() && level != ExperienceLevel.DIRECT) {
            out.append(" (via ").append(String.join(", ", via)).append(')');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------

    /**
     * He has used it.
     *
     * <p>The only construction that licenses a direct claim, and it is reachable
     * only from an index entry - which exists only because a line of his resume
     * names the thing.
     */
    static Positioning direct(String subject, ExperienceIndex.Entry entry) {
        return new Positioning(subject, ExperienceLevel.DIRECT, List.of(entry), List.of(),
                List.of("that you have worked with " + entry.display(),
                        "where you used it: " + entry.depth().phrase(),
                        "what you built with it, from the evidence"),
                List.of("a number of years",
                        "any level word - expert, advanced, extensive",
                        "any technology not in the evidence"),
                "Answer directly. Say what you used it for, from the evidence, and stop.");
    }

    static Positioning adjacent(String subject, List<ExperienceIndex.Entry> evidence,
            List<String> via) {
        return new Positioning(subject, ExperienceLevel.ADJACENT, evidence, via,
                List.of("that you have not worked with " + subject + " directly",
                        "the related technologies in the evidence, by name",
                        "that the underlying ideas are familiar",
                        "that you are comfortable picking things up quickly"),
                List.of("that you have used " + subject,
                        "that you have deployed, run, managed or built anything with "
                                + subject,
                        "any technology not in the evidence",
                        "a number of years"),
                "Open by saying you have not used it directly. Connect the closest "
                        + "evidence by name. Close on being able to get productive "
                        + "quickly - once, briefly.");
    }

    static Positioning conceptual(String subject, List<ExperienceIndex.Entry> evidence,
            List<String> via) {
        return new Positioning(subject, ExperienceLevel.CONCEPTUAL, evidence, via,
                List.of("that you have not used " + subject,
                        "the ideas it implements, which you have met: "
                                + String.join(", ", via),
                        "the technologies in the evidence that took you there",
                        "that you are comfortable picking things up quickly"),
                List.of("that you have used " + subject,
                        "that the connection is direct experience",
                        "any technology not in the evidence"),
                "Say you have not used the tool, name the idea behind it that you have "
                        + "worked on, and keep it short. This is a weaker link than "
                        + "adjacency and should not be oversold.");
    }

    static Positioning transferable(String subject, List<ExperienceIndex.Entry> evidence,
            List<String> via) {
        return new Positioning(subject, ExperienceLevel.TRANSFERABLE, evidence, via,
                List.of("that you have not worked with " + subject,
                        "the engineering ground you do have: " + String.join(", ", via),
                        "that you are comfortable picking things up quickly"),
                List.of("that you have used " + subject,
                        "any specific claim about " + subject,
                        "any technology not in the evidence"),
                "Two sentences. Say you have not used it, name the engineering you have "
                        + "done that is genuinely related, and stop. Do not stretch for a "
                        + "connection.");
    }

    /**
     * Nothing connects, and that is still an answer.
     *
     * <p>"Not yet, and I pick things up quickly" is true, reasonable and better
     * than a blank box - for an <em>experience</em> question. The category gate
     * upstream is what stops this reaching a question about a visa.
     */
    static Positioning none(String subject) {
        return new Positioning(subject, ExperienceLevel.NONE, List.of(), List.of(),
                List.of("that you have not worked with " + subject,
                        "that you are comfortable picking up unfamiliar technologies"),
                List.of("that you have used " + subject,
                        "any related experience, because there is none on record",
                        "any technology at all"),
                "One sentence. You have not used it. Do not reach for a connection that "
                        + "is not there.");
    }
}
