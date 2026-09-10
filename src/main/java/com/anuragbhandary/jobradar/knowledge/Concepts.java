package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.knowledge.Concept.AnswerType;
import com.anuragbhandary.jobradar.knowledge.Concept.Category;
import com.anuragbhandary.jobradar.knowledge.Scope.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every concept the tool knows, and how a form field becomes one.
 *
 * <h2>Deterministic first</h2>
 * The path is field &rarr; {@link FieldClassifier} &rarr; {@link FieldKind}
 * &rarr; concept, and it is the only path used during a live application.
 * {@code FieldClassifier} is forty ordered rules, each written after a real
 * misclassification, and none of that judgement is thrown away: this class is a
 * lookup table on top of it, not a replacement for it.
 *
 * <p>Aliases catch what the kinds cannot - questions that are not form-field
 * archetypes at all ("Do you have a valid passport?", "Why do you want to work
 * here?"). They are matched on the same normalised label the classifier uses, so
 * there is one definition of "the same question".
 *
 * <p>Anything neither route places is {@link Concept#UNRECOGNISED}. A model may
 * later be asked which existing concept it belongs to; it may never invent one.
 */
public final class Concepts {

    private Concepts() {
    }

    // ------------------------------------------------------------------
    // Scope sets, named so the intent is readable at each concept
    // ------------------------------------------------------------------

    /** A fact about him that does not change with the job. */
    private static final Set<Level> STABLE =
            Set.of(Level.GLOBAL, Level.COUNTRY, Level.COMPANY, Level.APPLICATION);

    /**
     * A fact whose answer depends on where the job is.
     *
     * <p><b>No GLOBAL.</b> This set is the enforcement of the rule the whole
     * phase exists for: there is no way to write "I require sponsorship" as a
     * universal truth, because there is no scope to write it at.
     */
    private static final Set<Level> CONTEXTUAL =
            Set.of(Level.COUNTRY, Level.WORK_MODE, Level.STRATEGIC_CLASS,
                    Level.COMPANY, Level.APPLICATION);

    /** Something about this employer, which says nothing about the next one. */
    private static final Set<Level> PER_COMPANY =
            Set.of(Level.COMPANY, Level.APPLICATION);

    private static final Set<Level> THIS_ONE_ONLY = Set.of(Level.APPLICATION);

    // ------------------------------------------------------------------

    private static final List<Concept> ALL = new ArrayList<>();
    private static final Map<String, Concept> BY_ID = new LinkedHashMap<>();
    private static final Map<FieldKind, Concept> BY_KIND = new LinkedHashMap<>();

    /** A stable personal detail: global by default, never drafted by a model. */
    private static Concept fact(FieldKind kind, String id, String label, AnswerType type,
            String... aliases) {
        return register(kind, new Concept(id, label, Category.FACTUAL, type,
                false, false, false, true, Level.GLOBAL, STABLE, List.of(aliases)));
    }

    /**
     * A voluntary self-identification. Absent is a real answer, not a gap.
     *
     * <p>{@link Category#SENSITIVE}: these are regulated declarations, so nothing
     * may be inferred, positioned or drafted for them. Only what he has stated.
     */
    private static Concept voluntary(FieldKind kind, String id, String label) {
        return register(kind, new Concept(id, label, Category.SENSITIVE,
                AnswerType.CHOICE, false, false, false, true, Level.GLOBAL, STABLE,
                List.of()));
    }

    /** Depends on the country, the work mode or the employer. Never global. */
    private static Concept contextual(FieldKind kind, String id, String label,
            AnswerType type, boolean volatileFact, String... aliases) {
        return register(kind, new Concept(id, label, Category.CONTEXTUAL, type,
                true, volatileFact, false, true, Level.COUNTRY, CONTEXTUAL,
                List.of(aliases)));
    }

    /**
     * "Do you have experience with X?" - the only category positioning may run for.
     *
     * <p>Scoped globally because experience is a fact about him rather than about
     * an application, and never auto-resolvable: an experience answer goes to an
     * employer as prose under his name, so he reads it first.
     */
    private static Concept experience(String id, String label, String... aliases) {
        return register(null, new Concept(id, label, Category.EXPERIENCE,
                AnswerType.LONG_TEXT, false, false, true, false, Level.GLOBAL, STABLE,
                List.of(aliases)));
    }

    /** Prose about him, the company or the role. A model may draft it. */
    private static Concept openEnded(String id, String label, Level defaultScope,
            Set<Level> allowed, String... aliases) {
        return register(null, new Concept(id, label, Category.OPEN_ENDED,
                AnswerType.LONG_TEXT, false, false, true, false, defaultScope, allowed,
                List.of(aliases)));
    }

    private static Concept register(FieldKind kind, Concept concept) {
        ALL.add(concept);
        BY_ID.put(concept.id(), concept);
        if (kind != null) {
            BY_KIND.put(kind, concept);
        }
        return concept;
    }

    // ------------------------------------------------------------------
    // Identity and contact. Copied from the profile, correct by construction.
    // ------------------------------------------------------------------

    public static final Concept FIRST_NAME =
            fact(FieldKind.FIRST_NAME, "identity.first_name", "First name", AnswerType.TEXT);
    public static final Concept MIDDLE_NAME =
            fact(FieldKind.MIDDLE_NAME, "identity.middle_name", "Middle name", AnswerType.TEXT);
    public static final Concept LAST_NAME =
            fact(FieldKind.LAST_NAME, "identity.last_name", "Last name", AnswerType.TEXT);
    public static final Concept FULL_NAME =
            fact(FieldKind.FULL_NAME, "identity.full_name", "Full name", AnswerType.TEXT);
    public static final Concept PREFERRED_NAME = fact(FieldKind.PREFERRED_NAME,
            "identity.preferred_name", "Preferred name", AnswerType.TEXT);
    public static final Concept EMAIL =
            fact(FieldKind.EMAIL, "contact.email", "Email address", AnswerType.TEXT);
    public static final Concept PHONE =
            fact(FieldKind.PHONE, "contact.phone", "Phone number", AnswerType.TEXT);
    public static final Concept PHONE_COUNTRY_CODE = fact(FieldKind.PHONE_COUNTRY_CODE,
            "contact.phone_country_code", "Phone country code", AnswerType.TEXT);
    public static final Concept ADDRESS_LINE_1 = fact(FieldKind.ADDRESS_LINE_1,
            "address.line1", "Address line 1", AnswerType.TEXT);
    public static final Concept ADDRESS_LINE_2 = fact(FieldKind.ADDRESS_LINE_2,
            "address.line2", "Address line 2", AnswerType.TEXT);
    public static final Concept CITY =
            fact(FieldKind.CITY, "address.city", "City", AnswerType.TEXT);
    public static final Concept STATE =
            fact(FieldKind.STATE, "address.state", "State or region", AnswerType.TEXT);
    public static final Concept POSTAL_CODE =
            fact(FieldKind.POSTAL_CODE, "address.postal_code", "Postal code", AnswerType.TEXT);
    public static final Concept COUNTRY =
            fact(FieldKind.COUNTRY, "address.country", "Country of residence", AnswerType.TEXT);
    public static final Concept NATIONALITY =
            fact(FieldKind.NATIONALITY, "identity.nationality", "Nationality", AnswerType.TEXT);
    public static final Concept LINKEDIN =
            fact(FieldKind.LINKEDIN, "link.linkedin", "LinkedIn profile", AnswerType.TEXT);
    public static final Concept GITHUB =
            fact(FieldKind.GITHUB, "link.github", "GitHub profile", AnswerType.TEXT);
    public static final Concept PORTFOLIO =
            fact(FieldKind.PORTFOLIO, "link.portfolio", "Portfolio", AnswerType.TEXT);
    public static final Concept OTHER_LINK =
            fact(FieldKind.OTHER_LINK, "link.other", "Other link", AnswerType.TEXT);

    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    public static final Concept RESUME_UPLOAD =
            fact(FieldKind.RESUME_UPLOAD, "document.resume", "Resume", AnswerType.FILE);
    public static final Concept COVER_LETTER_UPLOAD = register(FieldKind.COVER_LETTER_UPLOAD,
            new Concept("document.cover_letter_upload", "Cover letter attachment", Category.FACTUAL,
                    AnswerType.NONE, false, false, false, true,
                    Level.APPLICATION, THIS_ONE_ONLY, List.of()));

    /**
     * A cover letter typed into a box. AI-eligible and never auto-filled: it is
     * prose sent to an employer under his name.
     */
    public static final Concept COVER_LETTER_TEXT = register(FieldKind.COVER_LETTER_TEXT,
            new Concept("document.cover_letter_text", "Cover letter", Category.OPEN_ENDED, AnswerType.LONG_TEXT,
                    false, true, true, false,
                    Level.APPLICATION, THIS_ONE_ONLY, List.of()));

    // ------------------------------------------------------------------
    // The context-sensitive ones. The reason this package exists.
    // ------------------------------------------------------------------

    public static final Concept SPONSORSHIP_REQUIRED = contextual(
            FieldKind.SPONSORSHIP_REQUIRED, "sponsorship.required",
            "Will you require visa sponsorship?", AnswerType.BOOLEAN, false,
            "do you require sponsorship", "will you require sponsorship",
            "require visa sponsorship", "need sponsorship",
            "now or in the future require sponsorship",
            "do you require employer sponsorship", "do you need visa support",
            "do you have eu citizenship or");

    public static final Concept WORK_AUTHORISATION = contextual(
            FieldKind.WORK_AUTHORISATION, "work_authorization",
            "Are you legally authorised to work here?", AnswerType.BOOLEAN, false,
            "legally authorized to work", "legally authorised to work",
            "right to work", "eligible to work", "work authorization",
            "status that allows you to work", "citizen permanent resident",
            "legally eligible to work in the country");

    public static final Concept RELOCATION_WILLING = contextual(
            FieldKind.RELOCATION_WILLING, "willing_to_relocate",
            "Are you willing to relocate?", AnswerType.BOOLEAN, false,
            "willing to relocate", "open to relocation");

    public static final Concept SALARY_EXPECTATION = contextual(
            FieldKind.SALARY_EXPECTATION, "salary_expectation",
            "Salary expectation", AnswerType.TEXT, true,
            "salary expectation", "expected salary", "compensation expectation",
            "expected ctc", "desired pay");

    /** Where he may sit. Its own concept because forms ask it separately. */
    public static final Concept REMOTE_WORK_WILLING = register(null,
            new Concept("remote_eligibility", "Are you able to work remotely?", Category.CONTEXTUAL,
                    AnswerType.BOOLEAN, true, false, false, true,
                    Level.WORK_MODE, CONTEXTUAL,
                    List.of("able and willing to work remotely", "work remotely",
                            "willing to work from office", "work from the office",
                            "comfortable working remotely")));

    // ------------------------------------------------------------------
    // Volatile facts. True today, not necessarily in six months.
    // ------------------------------------------------------------------

    public static final Concept NOTICE_PERIOD = register(FieldKind.NOTICE_PERIOD,
            new Concept("notice_period", "Notice period", Category.FACTUAL, AnswerType.TEXT,
                    false, true, false, true, Level.GLOBAL, STABLE,
                    List.of("notice period", "how much notice")));

    public static final Concept START_DATE = register(FieldKind.START_DATE,
            new Concept("availability_start", "Earliest start date", Category.FACTUAL, AnswerType.TEXT,
                    false, true, false, true, Level.GLOBAL, STABLE,
                    // No bare "availability". It is inside "I confirm my
                    // availability for at least 32 hours per week", which is a
                    // confirmation checkbox, and answering it "Immediately" is
                    // the same class of bug as "ethnicity" containing "city".
                    // The deterministic classifier already covers the real
                    // phrasings; aliases only run when it declines.
                    List.of("earliest start", "when can you start",
                            "available to start", "start date")));

    public static final Concept CURRENTLY_EMPLOYED = register(null,
            new Concept("currently_employed", "Are you currently employed?", Category.FACTUAL,
                    AnswerType.BOOLEAN, false, true, false, true, Level.GLOBAL, STABLE,
                    List.of("currently employed", "are you employed")));

    // ------------------------------------------------------------------
    // Voluntary self-identification
    // ------------------------------------------------------------------

    public static final Concept GENDER = voluntary(FieldKind.GENDER, "eeo.gender", "Gender");
    public static final Concept RACE = voluntary(FieldKind.RACE, "eeo.race", "Race");
    public static final Concept HISPANIC_LATINO =
            voluntary(FieldKind.HISPANIC_LATINO, "eeo.hispanic_latino", "Hispanic or Latino");
    public static final Concept VETERAN_STATUS =
            voluntary(FieldKind.VETERAN_STATUS, "eeo.veteran", "Veteran status");
    public static final Concept DISABILITY_STATUS =
            voluntary(FieldKind.DISABILITY_STATUS, "eeo.disability", "Disability status");
    public static final Concept PRONOUNS =
            voluntary(FieldKind.PRONOUNS, "eeo.pronouns", "Pronouns");

    // ------------------------------------------------------------------
    // Stable facts a form asks about that are not profile fields
    // ------------------------------------------------------------------

    public static final Concept REFERRAL_SOURCE = fact(FieldKind.REFERRAL_SOURCE,
            "how_did_you_hear", "How did you hear about us?", AnswerType.TEXT,
            "how did you hear", "how did you find", "referral source");

    public static final Concept EDUCATION_DEGREE = register(null,
            new Concept("education_degree", "Highest level of education", Category.FACTUAL,
                    AnswerType.CHOICE, false, false, false, true, Level.GLOBAL, STABLE,
                    List.of("highest level of education", "highest degree",
                            "level of education completed")));

    public static final Concept AGE_OVER_18 = register(null,
            new Concept("age_over_18", "Are you at least 18?", Category.FACTUAL, AnswerType.BOOLEAN,
                    false, false, false, true, Level.GLOBAL, STABLE,
                    List.of("are you at least 18", "at least 18 years", "over 18")));

    public static final Concept VALID_PASSPORT = register(null,
            new Concept("valid_passport", "Do you hold a valid passport?", Category.FACTUAL,
                    AnswerType.BOOLEAN, false, true, false, true, Level.GLOBAL, STABLE,
                    List.of("valid passport", "do you have a passport")));

    public static final Concept YEARS_OF_EXPERIENCE = register(null,
            new Concept("years_of_experience", "Years of professional experience", Category.FACTUAL,
                    AnswerType.TEXT, false, true, false, true, Level.GLOBAL, STABLE,
                    List.of("years of experience", "years of professional",
                            "how many years")));

    // ------------------------------------------------------------------
    // Per-employer. These leak the most obviously, so they are scoped tightest.
    // ------------------------------------------------------------------

    public static final Concept WHY_COMPANY = register(null,
            new Concept("why_company", "Why do you want to work here?", Category.OPEN_ENDED,
                    AnswerType.LONG_TEXT, false, false, true, false,
                    Level.COMPANY, PER_COMPANY,
                    List.of("why do you want to work", "why are you interested in",
                            "why us", "why this company", "what interests you about")));

    public static final Concept WHY_ROLE = register(null,
            new Concept("why_role", "Why this role?", Category.OPEN_ENDED, AnswerType.LONG_TEXT,
                    false, false, true, false, Level.APPLICATION, THIS_ONE_ONLY,
                    List.of("why this role", "why are you applying for this",
                            "what excites you about this role")));

    public static final Concept PREVIOUSLY_APPLIED = register(null,
            new Concept("previously_applied", "Have you applied here before?", Category.FACTUAL,
                    AnswerType.BOOLEAN, false, true, false, true,
                    Level.COMPANY, PER_COMPANY,
                    List.of("previously applied", "applied to us before",
                            "have you applied before")));

    public static final Concept WORKED_HERE_BEFORE = register(null,
            new Concept("worked_here_before", "Have you worked here before?", Category.FACTUAL,
                    AnswerType.BOOLEAN, false, false, false, true,
                    Level.COMPANY, PER_COMPANY,
                    List.of("have you worked for", "previously worked for",
                            "former employee")));

    /**
     * "Are you related to anyone who works here?"
     *
     * <p>Scoped globally, which is a change, and the reasoning is worth keeping.
     * The question is worded per-company and the <em>answer</em> is a fact about
     * his family: he has no relatives at any of these employers, and finding out
     * otherwise would mean reading a staff directory, which nobody does. Left at
     * COMPANY it resolved to UNKNOWN on every Greenhouse form forever - an
     * interruption on every application, for a question he answers the same way
     * every time.
     *
     * <p>COMPANY stays allowed, so a company where the answer differs can be
     * given its own narrower rule that outranks the global one. And the global
     * rule is not created for him: the audit proposes it and he approves it,
     * because a wrong answer here is a declaration on a form.
     *
     * <p>{@code worked_here_before} deliberately did <b>not</b> get the same
     * treatment. There is an employer where that answer is yes and he knows which
     * one, so it is genuinely per-company.
     */
    public static final Concept RELATED_TO_EMPLOYEE = register(null,
            new Concept("related_to_employee", "Are you related to an employee here?",
                    Category.FACTUAL, AnswerType.BOOLEAN, false, false, false, true,
                    Level.GLOBAL, STABLE,
                    List.of("related to any current employees", "related to an employee",
                            "family member who works", "relatives employed by")));

    // ------------------------------------------------------------------
    // Experience, and the questions the old system had no home for
    // ------------------------------------------------------------------

    /**
     * "Do you have experience with X?" for any X.
     *
     * <p>One concept rather than four hundred. A concept per technology would
     * mean a registry entry, an alias list and a scope decision for every tool a
     * board might name, invented in advance and out of date by the next quarter -
     * so the <em>subject</em> is read out of the question and the answer comes
     * from evidence, while this concept carries the rules that apply to all of
     * them: a model may draft, nothing auto-fills, and the answer is scoped to
     * the application it was written for.
     *
     * <p>Scoped to the application on purpose. The evidence is stable and the
     * wording is not: the same Kubernetes question from a different company in a
     * different role deserves a fresh sentence built from the same resume, not a
     * paragraph copied from a form filled in March.
     */
    public static final Concept TECHNOLOGY_EXPERIENCE = register(null,
            new Concept("experience.technology", "Experience with a technology",
                    Category.EXPERIENCE, AnswerType.LONG_TEXT, false, false, true, false,
                    Level.APPLICATION, THIS_ONE_ONLY,
                    // No aliases, deliberately. An alias is a substring match on
                    // a raw label, so "experience with" would claim "do you have
                    // experience with a criminal conviction" - the same shape of
                    // bug as the bare "availability" alias that once answered a
                    // 32-hours-a-week checkbox with "Immediately".
                    //
                    // This concept is reached through ExperienceQuestion instead,
                    // which refuses anything naming a visa, a salary, an EEO
                    // category or a number of years before it looks for a
                    // subject. One guarded path in, and no second unguarded one.
                    List.of()));

    /**
     * "Which working setup gets the best work out of you?"
     *
     * <p>A real question from a real form, answered once and then stored as
     * {@code legacy.which_working_setup_gets_the_best_work_out_of_yo} - a key
     * generated by truncating the question at fifty characters. It is a stable
     * preference of his, it is asked in a dozen wordings, and it should be
     * answered from what he has already said rather than asked again.
     */
    public static final Concept WORKING_STYLE = openEnded(
            "working_style", "What working setup suits you?", Level.GLOBAL, STABLE,
            "working setup gets the best work", "what kind of work energises",
            "how do you prefer to work", "ideal working environment",
            "what motivates you at work");

    /** "How do you like to work with other engineers?" - likewise stable, likewise asked often. */
    public static final Concept COLLABORATION_STYLE = openEnded(
            "collaboration_style", "How do you work with other engineers?",
            Level.GLOBAL, STABLE,
            "work with other engineers", "how do you collaborate",
            "working with a team", "approach to code review",
            "how do you handle disagreement");

    /**
     * The one bespoke question that is really an experience question.
     *
     * <p>"Which best describes your experience with event-driven systems?" was
     * stored as a legacy key and re-asked on every form that phrased it
     * differently. It is answerable from Kafka, streaming and the two projects
     * built on them, so it is an alias of the technology concept rather than a
     * question of its own.
     */
    public static final Concept EVENT_DRIVEN_EXPERIENCE = experience(
            "experience.event_driven", "Experience with event-driven systems",
            "experience with event-driven", "event-driven systems",
            "queues, streams, or event sourcing", "message-driven architecture");

    // ------------------------------------------------------------------
    // Recognised and deliberately never answered
    // ------------------------------------------------------------------

    public static final Concept CONSENT = register(FieldKind.CONSENT,
            new Concept("consent", "Consent checkbox", Category.SENSITIVE, AnswerType.NONE,
                    false, false, false, false,
                    Level.APPLICATION, THIS_ONE_ONLY, List.of()));

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    public static List<Concept> all() {
        return List.copyOf(ALL);
    }

    public static Optional<Concept> byId(String id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id));
    }

    /**
     * The concept for a classified field kind.
     *
     * <p>{@link FieldKind#UNKNOWN} has no entry on purpose: it means the
     * classifier declined to place the field, and the caller should try aliases
     * before giving up.
     */
    public static Optional<Concept> forKind(FieldKind kind) {
        return Optional.ofNullable(kind == null ? null : BY_KIND.get(kind));
    }

    /**
     * The concept whose alias best matches a question.
     *
     * <p>Longest alias wins, so "legally eligible to work in the country" beats a
     * shorter overlapping phrase rather than whichever happened to be declared
     * first. Both sides are normalised through {@link FieldClassifier#normalise}
     * - the label has already had its punctuation flattened, and an alias written
     * as a person would quote the question has not.
     */
    public static Optional<Concept> byAlias(String rawLabel) {
        String label = FieldClassifier.normalise(rawLabel);
        if (label.isBlank()) {
            return Optional.empty();
        }
        Concept best = null;
        int bestLength = 0;
        for (Concept concept : ALL) {
            for (String alias : concept.aliases()) {
                String needle = FieldClassifier.normalise(alias);
                if (!needle.isBlank() && label.contains(needle) && needle.length() > bestLength) {
                    best = concept;
                    bestLength = needle.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
