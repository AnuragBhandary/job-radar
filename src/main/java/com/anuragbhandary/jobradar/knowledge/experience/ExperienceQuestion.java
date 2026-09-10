package com.anuragbhandary.jobradar.knowledge.experience;

import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises "do you have experience with X?", and says what X is.
 *
 * <p>Needed because these questions arrive <em>unclassified</em>. There is no
 * concept for Kubernetes and there never will be one - a concept per technology
 * would be four hundred concepts, each with its own aliases, and boards invent a
 * new one every quarter. So the subject is read out of the question text and the
 * evidence decides the answer, which is the only arrangement that scales past the
 * vocabulary someone thought of in advance.
 *
 * <p>Deterministic: a phrase match and a vocabulary lookup, no model. It runs on
 * every unrecognised question on every form, and a model call there would be a
 * model call per field.
 *
 * <h2>What it deliberately does not match</h2>
 * "How many years of experience do you have?" is a factual question with a
 * number for an answer, not an invitation to position anything - so a question
 * asking for a <em>quantity</em> is refused even though it contains the word
 * experience. Same for anything naming a country, a visa or a permit: those
 * belong to the contextual derivations and must never reach this path.
 */
public final class ExperienceQuestion {

    private ExperienceQuestion() {
    }

    /** The shapes a board actually uses to ask whether he has used something. */
    private static final List<Pattern> ASKS_EXPERIENCE = List.of(
            Pattern.compile("\\b(do|have) you (have|had|worked|used|got)\\b"),
            Pattern.compile("\\byour experience (with|in|of)\\b"),
            Pattern.compile("\\bexperience (with|in|using|of)\\b"),
            // "How familiar ARE YOU with GraphQL" - the words between are why
            // this is not a fixed phrase.
            Pattern.compile("\\bfamiliar(ity)?\\b.{0,20}\\bwith\\b"),
            Pattern.compile("\\bproficien(t|cy) (with|in)\\b"),
            Pattern.compile("\\bhands[- ]on (with|experience)\\b"),
            Pattern.compile("\\bcomfortable (with|working with)\\b"),
            Pattern.compile("\\bworked (with|on|using)\\b"),
            Pattern.compile("\\bskilled (with|in)\\b"),
            Pattern.compile("\\bknowledge of\\b"),
            Pattern.compile("\\bexposure to\\b"),
            Pattern.compile("\\brate your\\b"));

    /**
     * Questions that contain "experience" and are not this.
     *
     * <p>Each of these was a real misfire waiting to happen. A years question
     * wants a number; anything about permits belongs to the sponsorship
     * derivation and would be catastrophic to answer with positioning prose.
     */
    private static final List<Pattern> NOT_THIS = List.of(
            Pattern.compile("\\bhow many years\\b"),
            Pattern.compile("\\byears of (professional )?experience\\b"),
            Pattern.compile("\\b(visa|sponsor|permit|authoris|authoriz|citizen|eligib)"),
            Pattern.compile("\\b(salary|compensation|notice period|start date)\\b"),
            Pattern.compile("\\b(gender|race|ethnic|veteran|disab|pronoun)\\b"),
            Pattern.compile("\\b(relocat|remote|hybrid|onsite|office)\\b"),
            Pattern.compile("\\bcriminal|conviction|background check\\b"));

    /**
     * @param subject       what the question asked about, as the board wrote it
     * @param vocabularyHit true when the subject is a term the technology
     *                      vocabulary knows, which is what separates "Kubernetes"
     *                      from a phrase this class guessed at
     */
    public record Subject(String subject, boolean vocabularyHit) {
    }

    /**
     * Whether this is a technology-experience question, and what it is about.
     *
     * @return empty when the question is not of this kind, or is of this kind and
     *         names nothing recognisable. Empty is the safe answer: it means the
     *         question carries on down the ordinary path and, if nothing answers
     *         it, reaches him.
     */
    public static Optional<Subject> subjectOf(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String text = label.toLowerCase(Locale.ROOT);
        if (NOT_THIS.stream().anyMatch(pattern -> pattern.matcher(text).find())) {
            return Optional.empty();
        }
        if (ASKS_EXPERIENCE.stream().noneMatch(pattern -> pattern.matcher(text).find())) {
            return Optional.empty();
        }

        // A vocabulary term is the strong case: the board named something the
        // system can look up, in his resume and in the graph.
        Set<String> named = new LinkedHashSet<>(TechVocabulary.found(label));
        if (!named.isEmpty()) {
            return Optional.of(new Subject(longest(named), true));
        }
        // The graph knows a few things the vocabulary does not - the ideas, like
        // "container orchestration" - and a question may name one of those.
        for (String known : SkillGraph.everyTechnology()) {
            if (text.contains(known)) {
                return Optional.of(new Subject(known, true));
            }
        }
        for (String idea : ideasNamedIn(text)) {
            return Optional.of(new Subject(idea, false));
        }
        // A technology nobody has heard of is still a technology question, and
        // still not his problem: "I haven't used COBOL" is a true and complete
        // answer that he should not have to type. So the subject is read out of
        // the sentence, and the positioner will honestly find nothing.
        return afterTheTrigger(label);
    }

    /**
     * The words the question is about, when none of them are recognised.
     *
     * <p>Bounded deliberately. It takes at most three words following the phrase
     * that made this an experience question, stops at punctuation, and gives up
     * if what it finds is long or empty. That is enough for "COBOL", "SAP
     * HANA" and "Adobe Experience Manager", and not enough to start matching
     * clauses.
     *
     * <p>Safe because of where it lands. An unrecognised subject has no evidence
     * and no graph entry, so it can only reach {@link ExperienceLevel#NONE} -
     * whose entire licensed output is "I have not used this". A wrong subject
     * there produces a slightly odd sentence, not a false claim, and the
     * questions where a false claim would matter never reach this method.
     */
    private static Optional<Subject> afterTheTrigger(String label) {
        String text = label.toLowerCase(Locale.ROOT);
        for (Pattern trigger : ASKS_EXPERIENCE) {
            Matcher matcher = trigger.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            String rest = label.substring(Math.min(label.length(), matcher.end()));
            // Up to the first thing that ends a noun phrase.
            String phrase = rest.split("[?.,;:()\\[\\]]|\\b(and|or|in|at|for|to)\\b", 2)[0]
                    .trim();
            String[] words = phrase.split("\\s+");
            if (words.length == 0 || phrase.isBlank() || words.length > 3) {
                continue;
            }
            String subject = String.join(" ", words).trim();
            if (subject.length() < 2 || subject.length() > 40) {
                continue;
            }
            return Optional.of(new Subject(subject, false));
        }
        return Optional.empty();
    }

    /**
     * Ideas a question may ask about that are not products.
     *
     * <p>"Have you worked with container orchestration?" names no tool and is
     * plainly the same question as the Kubernetes one. Kept short and explicit:
     * a general noun-phrase extractor here would start matching "our team" and
     * "this role".
     */
    private static List<String> ideasNamedIn(String text) {
        List<String> found = new java.util.ArrayList<>();
        for (String idea : List.of(
                "container orchestration", "containerisation", "containerization",
                "distributed systems", "event-driven", "event driven", "streaming",
                "microservices", "infrastructure as code", "message queues",
                "real-time systems", "job scheduling", "workflow orchestration",
                "observability", "ci/cd", "cloud infrastructure", "api design",
                "relational databases", "asynchronous processing", "caching")) {
            if (text.contains(idea)) {
                found.add(idea);
            }
        }
        return found;
    }

    /** The most specific term wins: "spring boot" over "spring", "k8s" over "go". */
    private static String longest(Set<String> named) {
        return named.stream()
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow();
    }
}
