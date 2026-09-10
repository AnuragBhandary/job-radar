package com.anuragbhandary.jobradar.knowledge.experience;

import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a drafted answer against what the evidence actually allows.
 *
 * <p>The prompt asks a model not to invent experience. This is the part that
 * finds out whether it did. A prompt is a request; a validator is a rule, and
 * the difference matters when the output goes to an employer under his name and
 * he may be asked about it in an interview six weeks later.
 *
 * <h2>Four checks</h2>
 * <ol>
 *   <li><b>The disclaimer.</b> At every level but DIRECT the answer has to say he
 *       has not used the thing. An answer that omits it reads as a yes.</li>
 *   <li><b>No use of the subject.</b> "I deployed Kubernetes clusters" is
 *       rejected when the index has no Kubernetes. This is the check the whole
 *       feature turns on.</li>
 *   <li><b>Only technologies he has.</b> Every technology the answer names has to
 *       be somewhere in his own material. A fluent paragraph about his Terraform
 *       work fails no style rule and is a fabrication. At {@link
 *       ExperienceLevel#NONE} the bar is higher still: no technology at all,
 *       because the positioning already said there is nothing relevant and
 *       offering some anyway is reaching.</li>
 *   <li><b>No inflation.</b> Years, "expert", "extensive", "advanced" - rejected
 *       unless the evidence carries them, which it never does, because a resume
 *       bullet is not a seniority claim.</li>
 * </ol>
 *
 * <p>A rejection returns the reason and the reason is shown. A check that fails
 * silently and regenerates looks like a slow model rather than like a model that
 * tried to make something up.
 */
public final class ClaimValidator {

    private ClaimValidator() {
    }

    /**
     * Phrases that turn a defensible answer into an indefensible one.
     *
     * <p>Every one is a claim about depth or duration that nothing in a resume
     * can support. They are rejected outright rather than trimmed, because the
     * sentence around them is usually built on them.
     */
    private static final List<Pattern> INFLATION = List.of(
            Pattern.compile("\\b\\d+\\+?\\s*(years?|yrs?)\\b", Pattern.CASE_INSENSITIVE),
            // Written out, which is how a model writes it. "three years" passed
            // a digit-only check and is exactly the same claim.
            Pattern.compile("\\b(one|two|three|four|five|six|seven|eight|nine|ten|"
                    + "several|many|numerous|couple of)\\s+years\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexpert(ise)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bextensive(ly)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\badvanced (knowledge|experience|proficiency)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdeep (expertise|knowledge)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(mastery|mastered)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bspecialis[ed]|specializ[ed]\\b", Pattern.CASE_INSENSITIVE));

    /** The shapes an English sentence uses to claim having used something. */
    private static final String USE_VERBS =
            "used|worked with|worked on|built|deployed|ran|run|managed|maintained|"
            + "implemented|operated|administered|configured|migrated|scaled|"
            + "orchestrated|shipped|delivered|developed";

    /**
     * Ways of saying the opposite, which is what most of these answers open with.
     *
     * <p>Broad on purpose. An enumerated list of four phrases rejected "Kubernetes
     * is not something I've worked with directly", which is a perfectly honest
     * opening written in a shape the list did not contain. Any negation counts
     * here, and the claim check below is what catches an answer that negates
     * something else and then claims the subject anyway - the two run
     * independently for exactly that reason.
     */
    private static final Pattern DISCLAIMER = Pattern.compile(
            "\\b(not|haven't|hasn't|never|no direct|nor)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param problem what is wrong with the draft, in words a person can read.
     *                Empty when nothing is.
     */
    public record Verdict(boolean acceptable, String problem) {

        static final Verdict OK = new Verdict(true, null);

        static Verdict rejected(String problem) {
            return new Verdict(false, problem);
        }
    }

    /**
     * Whether this draft may be shown as an answer to this question.
     *
     * @param answer      the prose a model produced
     * @param positioning what the evidence licensed before it was written
     */
    public static Verdict check(String answer, Positioning positioning) {
        return check(answer, positioning, null);
    }

    /**
     * @param index everything his resume names, or null to check against this
     *              positioning's evidence alone. Supplied in the running
     *              application: naming FastAPI in a Kafka answer is honest,
     *              because FastAPI is his - the rule is that a draft may not name
     *              a technology <em>he does not have</em>, not that it may only
     *              name the three the positioner happened to pick.
     */
    public static Verdict check(String answer, Positioning positioning,
            ExperienceIndex index) {
        if (answer == null || answer.isBlank()) {
            return Verdict.rejected("the draft is empty");
        }
        String text = answer.toLowerCase(Locale.ROOT);
        String subject = positioning.subject().toLowerCase(Locale.ROOT);

        // 1. Say so.
        if (positioning.needsDisclaimer() && !DISCLAIMER.matcher(text).find()) {
            return Verdict.rejected(
                    "it never says you have not worked with " + positioning.subject()
                            + ", so it reads as a claim that you have");
        }

        // 2. Do not claim the subject.
        if (positioning.needsDisclaimer()) {
            Optional<String> claim = claimOfUse(text, subject);
            if (claim.isPresent()) {
                return Verdict.rejected("it says you " + claim.get() + " "
                        + positioning.subject() + ", and nothing in your material does");
            }
        }

        // 3. Only technologies he actually has.
        List<String> invented = uncitedTechnologies(answer, positioning, index);
        if (!invented.isEmpty()) {
            return Verdict.rejected("it brings in " + String.join(", ", invented)
                    + ", which your material does not support here");
        }

        // 4. Do not inflate.
        for (Pattern pattern : INFLATION) {
            Matcher matcher = pattern.matcher(answer);
            if (matcher.find()) {
                return Verdict.rejected("it claims '" + matcher.group()
                        + "', which nothing in your material supports");
            }
        }
        return Verdict.OK;
    }

    /**
     * Whether the answer says he used the very thing it is meant to disclaim.
     *
     * <p>Looks for a use verb within a short distance <em>before</em> the
     * subject, which is where English puts it. A whole-sentence search matches
     * "I haven't used Kubernetes, but I've built distributed systems" and rejects
     * a perfectly honest answer; the window is what tells the two apart.
     */
    private static Optional<String> claimOfUse(String text, String subject) {
        int at = text.indexOf(subject);
        while (at >= 0) {
            String before = text.substring(Math.max(0, at - 40), at);
            Matcher verb = Pattern.compile("\\b(" + USE_VERBS + ")\\b").matcher(before);
            String last = null;
            while (verb.find()) {
                last = verb.group();
            }
            // A negation between the verb and the subject flips it back: "have
            // not used Kubernetes" contains "used" and claims nothing.
            if (last != null && !DISCLAIMER.matcher(before).find()) {
                return Optional.of(last);
            }
            at = text.indexOf(subject, at + subject.length());
        }
        return Optional.empty();
    }

    /**
     * Technologies the answer names that the evidence does not.
     *
     * <p>Run through the same vocabulary the resume is, so "Postgres" in the
     * draft and "PostgreSQL" in the evidence are the same thing. The subject
     * itself is allowed - the answer is about it, and saying its name is not a
     * claim to have used it.
     */
    private static List<String> uncitedTechnologies(String answer, Positioning positioning,
            ExperienceIndex index) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(SkillGraph.key(positioning.subject()));
        allowed.addAll(TechVocabulary.found(positioning.subject()));
        for (ExperienceIndex.Entry entry : positioning.evidence()) {
            allowed.add(entry.term());
            allowed.addAll(TechVocabulary.found(entry.display()));
            entry.evidence().forEach(item -> {
                allowed.addAll(TechVocabulary.found(item.display()));
                allowed.addAll(TechVocabulary.found(item.where()));
            });
        }
        positioning.via().forEach(term -> allowed.add(SkillGraph.key(term)));
        // Anything else on his resume, except at NONE - where the positioning
        // said there is no relevant experience, and offering some anyway is the
        // reaching this check exists to stop.
        if (index != null && positioning.level() != ExperienceLevel.NONE) {
            allowed.addAll(index.terms());
        }

        List<String> invented = new ArrayList<>();
        for (String named : TechVocabulary.found(answer)) {
            if (!allowed.contains(SkillGraph.key(named)) && !invented.contains(named)) {
                invented.add(named);
            }
        }
        return invented;
    }
}
