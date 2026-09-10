package com.anuragbhandary.jobradar.knowledge;

import java.util.List;
import java.util.Set;

/**
 * What an application question is actually asking, independent of its wording.
 *
 * <p>The identity the knowledge system is keyed on, replacing the six-word
 * substring that {@code OpenQuestion.suggestedKey()} produced. That key was
 * derived from whichever board asked first, so "Are you at least 18 years of
 * age?" and "Are you at least 18 years old?" were two different pieces of
 * knowledge, and "do you require sponsorship" answered a German form and an
 * Indian one with the same string because there was nowhere to say they differ.
 *
 * @param id               stable, dotted, and never regenerated from a label -
 *                         it is what assertions are stored against
 * @param label            canonical wording, for review screens
 * @param answerType       what shape a valid answer has
 * @param contextSensitive the answer can change with the country, the work mode
 *                         or the employer. These may never be stored at
 *                         {@link Scope.Level#GLOBAL}, which is enforced by
 *                         {@link #allowsScope} rather than left to the caller.
 * @param volatileFact     the answer goes out of date on its own. Notice period,
 *                         availability and salary expectation all do; a degree
 *                         does not.
 * @param aiEligible       a model may draft an answer. False for anything
 *                         factual about him - a model has no way to know his
 *                         notice period and every reason to sound like it does.
 * @param autoResolvable   may be filled without a human reading it first, when
 *                         resolution is confident. False for anything sent to an
 *                         employer as prose.
 * @param defaultScope     what the learning UI offers first
 * @param allowedScopes    everything it may offer at all
 * @param category         what kind of question this is, and therefore what the
 *                         system is allowed to do to answer it. The gate on
 *                         experience positioning: a technology question may be
 *                         answered from adjacent evidence, and a legal one may
 *                         never be.
 * @param aliases          real question phrasings, normalised the same way
 *                         {@link com.anuragbhandary.jobradar.apply.form.FieldClassifier}
 *                         normalises a label
 */
public record Concept(
        String id,
        String label,
        Category category,
        AnswerType answerType,
        boolean contextSensitive,
        boolean volatileFact,
        boolean aiEligible,
        boolean autoResolvable,
        Scope.Level defaultScope,
        Set<Scope.Level> allowedScopes,
        List<String> aliases) {

    /**
     * What kind of question this is, and what may be done to answer it.
     *
     * <p>Added when the system learned to position adjacent experience. Before
     * it, every question the classifier could not place became "ask him" - so a
     * form asking about Kubernetes interrupted the applicant because the word was
     * absent from his resume, even though Docker, containerised services and
     * distributed systems were all in it.
     *
     * <p>The category is the gate. Positioning may run for {@link #EXPERIENCE}
     * and for nothing else, which is what stops the same machinery being pointed
     * at a question about visas.
     */
    public enum Category {

        /**
         * Answerable only from something known to be true.
         *
         * <p>Degree, passport, notice period, employment dates. If the fact is
         * missing the system asks; it does not reason its way to one.
         */
        FACTUAL,

        /**
         * The answer changes with the country, the work mode or the employer.
         *
         * <p>Sponsorship, work authorisation, relocation, salary. Derived
         * deterministically in Java from the application context, and
         * <strong>never</strong> by a model - the derivation is a matrix that can
         * be tested exhaustively and a model's version of it cannot.
         */
        CONTEXTUAL,

        /**
         * "Do you have experience with X?"
         *
         * <p>The only category experience positioning may run for. The evidence
         * decides what may be claimed; a model only decides how to say it.
         */
        EXPERIENCE,

        /**
         * Prose about him, the company or the role.
         *
         * <p>Why this company, why this role, how he likes to work. A model may
         * draft these from his own material and he approves them.
         */
        OPEN_ENDED,

        /**
         * Regulated, personal, or legally consequential.
         *
         * <p>Voluntary self-identification, criminal history, consent. Answered
         * from what he has explicitly stated and from nothing else - no
         * inference, no positioning, no drafting. A wrong answer here is not an
         * awkward interview question, it is a false declaration.
         */
        SENSITIVE
    }

    /**
     * What a valid answer looks like. Used to validate, never to generate.
     *
     * <p>{@link #accepts} is what makes that sentence true. The type was
     * documentation until two rows turned up in the live database holding a
     * <em>rule written in prose</em> where a yes/no belonged - "In all countries
     * other than India, the answer is yes, I need sponsorship", stored against a
     * BOOLEAN concept and outranking the derivation that gets it right. A type
     * nothing checks is a comment.
     */
    public enum AnswerType {
        BOOLEAN,
        /** One of a fixed set the form itself offers. */
        CHOICE,
        TEXT,
        /** A paragraph or more, and therefore something a model might draft. */
        LONG_TEXT,
        NUMBER,
        DATE,
        FILE,
        /** Recognised, and deliberately never answered. Consent boxes. */
        NONE;

    /**
         * Whether a stored value is a plausible answer of this type.
         *
         * <p>Deliberately loose, and deliberately not empty. It is not trying to
         * decide whether an answer is <em>correct</em> - that is not knowable here -
         * only whether it is the right shape to be typed into the control the
         * question uses. A sentence is not a boolean; a paragraph is not a date.
         *
         * <p>{@link #CHOICE} accepts anything short. The form's own options decide
         * what is valid there and this layer has never seen them, so a length bound
         * is the honest limit of what can be checked without them.
         */
        public boolean accepts(String value) {
            if (value == null || value.isBlank()) {
                // Absence is a separate question from validity: a voluntary field is
                // deliberately blank and a missing fact is missing. Neither is
                // malformed.
                return true;
            }
            String trimmed = value.trim();
            return switch (this) {
                case BOOLEAN -> readsAsBoolean(trimmed);
                case NUMBER -> trimmed.matches("[-+]?\\d{1,12}([.,]\\d+)?\\s*\\w{0,12}");
                case DATE -> readsAsDate(trimmed);
                // A choice is checked against the form's options, which this layer
                // has not seen. Length is all that can honestly be said here.
                case CHOICE -> trimmed.length() <= 120;
                case TEXT -> trimmed.length() <= 500;
                case FILE -> trimmed.length() <= 1024;
                case LONG_TEXT, NONE -> true;
            };
        }

        /**
         * Whether a string is a yes or a no.
         *
         * <p>Accepts the option strings boards actually use - "Yes, I am authorized
         * to work in the United States" is a boolean answer, and it is fifty
         * characters long. What it refuses is prose that happens to contain "yes"
         * somewhere in the middle of an explanation, which is the exact shape of the
         * two malformed rows this exists for.
         */
        private static boolean readsAsBoolean(String value) {
            String lower = value.toLowerCase(java.util.Locale.ROOT).trim();
            if (lower.length() > 120) {
                return false;
            }
            // More than one sentence is an explanation, not an answer.
            if (lower.replaceAll("[^.]", "").length() > 1
                    || lower.matches(".*\\.\\s+\\S.*")) {
                return false;
            }
            return lower.matches("^(yes|no|y|n|true|false|i\\s.*|we\\s.*|not?\\b.*"
                    + "|yes\\b.*|no\\b.*|none\\b.*|n/a)$");
        }

        private static boolean readsAsDate(String value) {
            if (value.length() > 40) {
                return false;
            }
            try {
                java.time.LocalDate.parse(value.trim());
                return true;
            } catch (java.time.format.DateTimeParseException e) {
                // Boards accept "Immediately", "ASAP" and "1 June 2027" in date
                // boxes, and so does the profile. A short phrase is allowed; a
                // paragraph is not.
                return value.trim().split("\\s+").length <= 5;
            }
        }
    }

    /**
     * The concept for a question nobody has classified.
     *
     * <p>Its own value rather than null, so an unrecognised question is a thing
     * that can be counted, reported and reviewed. A model is never allowed to
     * invent a concept during a live application; the most it may do is propose
     * that a question belongs to one that already exists.
     */
    public static final Concept UNRECOGNISED = new Concept(
            "unrecognised", "Unrecognised question", Category.FACTUAL, AnswerType.TEXT,
            true, false, false, false,
            Scope.Level.APPLICATION, Set.of(Scope.Level.APPLICATION), List.of());

    public boolean isUnrecognised() {
        return UNRECOGNISED.id().equals(id);
    }

    /**
     * Whether adjacent evidence may be used to answer this.
     *
     * <p>One category and no other. The check is here rather than at the call
     * site so that adding a concept cannot accidentally open positioning on a
     * question about work authorisation.
     */
    public boolean allowsExperiencePositioning() {
        return category == Category.EXPERIENCE;
    }

    /** True when only something he has explicitly stated may be used. */
    public boolean isSensitive() {
        return category == Category.SENSITIVE;
    }

    /**
     * Whether knowledge about this concept may be stored at this scope.
     *
     * <p>The guard that stops the dangerous generalisation. A context-sensitive
     * concept simply has no GLOBAL in its allowed set, so answering "yes, I need
     * sponsorship" on a German form cannot produce a rule that later answers an
     * Indian one - not because the UI declines to offer it, but because the
     * write path refuses it.
     */
    public boolean allowsScope(Scope.Level level) {
        return level != null && allowedScopes.contains(level);
    }

    public List<String> aliases() {
        return aliases == null ? List.of() : aliases;
    }

    public Set<Scope.Level> allowedScopes() {
        return allowedScopes == null ? Set.of(Scope.Level.APPLICATION) : allowedScopes;
    }
}
