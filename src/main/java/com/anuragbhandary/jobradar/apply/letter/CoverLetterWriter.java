package com.anuragbhandary.jobradar.apply.letter;

import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext.EvidenceUse;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext.RequirementEvidence;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.evidence.GroundedProse;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the letter that goes in the text box, and only when there is one.
 *
 * <p>Called by {@link com.anuragbhandary.jobradar.apply.ApplyService} solely after
 * a {@link com.anuragbhandary.jobradar.apply.form.FieldKind#COVER_LETTER_TEXT}
 * field has been found on the page. A form offering a cover-letter <em>upload</em>
 * gets nothing: an optional attachment on an ATS is read by nobody.
 *
 * <h2>Grounded on the application's evidence, nothing else</h2>
 * The writer is given an {@link ApplicationEvidenceContext} - the evidence the
 * resume of this same application was planned from - and not the profile or the
 * resume. The model sees three things kept apart: the job description, which is the
 * employer's requirements and says nothing about him; what he cannot claim for this
 * posting, spelled out; and the approved evidence items that are the only facts
 * about him. The letter is written from the strongest items printed on the resume,
 * so the PDF and the letter cannot disagree.
 *
 * <h2>The output is checked before it is used</h2>
 * {@link #validate} catches the model's habits - "3 years of experience",
 * "[Company Name]", a sign-off. {@link GroundedProse} then checks the letter against
 * the evidence it was given: every technology, figure, qualifier and name must be
 * there, a hedge on a figure must survive, and nothing the posting asks for that he
 * cannot claim may be presented as his. A letter that fails either is discarded
 * rather than repaired, and after one retry the deterministic template is used - it
 * is assembled from approved wordings and is always safe to send.
 *
 * <p>Every letter carries the evidence ids it was grounded on, for the review file
 * and the record.
 */
@Component
public class CoverLetterWriter {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterWriter.class);

    /** Most boards cap the box; this fits the smallest common one. */
    private static final int MAX_CHARS = 2000;

    /** Evidence items a letter is written from. The prompt says "at most two"; three gives it a choice. */
    static final int EVIDENCE_ITEMS = 3;

    /**
     * Phrases that mean the model invented something, or left a placeholder in.
     *
     * <p>"years of experience" is on the list without qualification: no evidence item
     * states a duration, so a model that writes one has invented it.
     */
    private static final List<String> FORBIDDEN = List.of(
            "years of experience", "years' experience", "years of professional",
            "[company", "[role", "[position", "[name", "{{", "insert ",
            "as an ai", "language model", "i am writing to express my strong interest");

    /**
     * @param evidenceIds the evidence items it was grounded on
     * @param drafted     true when a model wrote it, false for the template
     */
    public record CoverLetter(String text, List<String> evidenceIds, boolean drafted) {

        public CoverLetter {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    private final LlmClient llm;

    public CoverLetterWriter(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * @param limit the box's own maximum length where the form states one
     * @return the letter and its provenance, or empty when there is no printed
     *         evidence to ground one on. Empty is a valid outcome: an unanswered
     *         optional textarea beats an ungrounded letter.
     */
    public Optional<CoverLetter> write(Posting posting, String company,
            ApplicationEvidenceContext evidence, int limit) {

        if (evidence == null) {
            return Optional.empty();
        }
        List<EvidenceUse> facts = evidence.strongest(EVIDENCE_ITEMS);
        if (facts.isEmpty()) {
            log.info("No printed evidence to ground a letter on - none written");
            return Optional.empty();
        }
        int cap = limit > 0 ? Math.min(limit, MAX_CHARS) : MAX_CHARS;
        List<String> ids = facts.stream().map(EvidenceUse::id).toList();
        GroundedProse.Grounding grounding = GroundedProse.Grounding.of(facts,
                List.of(nullSafe(company), nullSafe(posting.getTitle())), evidence.notClaimable());

        if (llm.isUsable()) {
            String user = userPrompt(posting, company, facts, evidence);

            Draft first = draft(systemPrompt(cap), user, cap, grounding);
            if (first.text() != null) {
                return Optional.of(new CoverLetter(first.text(), ids, true));
            }
            // One retry, and only one. The second attempt is told exactly what was
            // wrong, which is a different request from the first; a third would be
            // the same request again.
            Draft second = draft(systemPrompt(cap), user + "\n\n" + first.rejection(), cap, grounding);
            if (second.text() != null) {
                log.info("Second draft accepted");
                return Optional.of(new CoverLetter(second.text(), ids, true));
            }
            log.info("Both drafts rejected - using the template");
        }
        String template = clean(template(posting, company, facts, evidence));
        return template.isBlank() ? Optional.empty()
                : Optional.of(new CoverLetter(template, ids, false));
    }

    /** Every reason a letter is not grounded on the evidence it was given. */
    public static List<String> groundingProblems(String text, GroundedProse.Grounding grounding) {
        return GroundedProse.problems(text, grounding);
    }

    private record Draft(String text, String rejection) {
    }

    /**
     * One attempt: generate, strip dash punctuation, then judge what is left.
     *
     * <p>Dashes are rewritten before judging because they are a formatting habit and
     * fixing one changes nothing the sentence claims; everything else is grounds for
     * rejection.
     */
    private Draft draft(String system, String user, int cap, GroundedProse.Grounding grounding) {
        Optional<String> raw = llm.complete(system, user);
        if (raw.isEmpty()) {
            return new Draft(null, "");
        }
        String text = clean(raw.get());

        if (!validate(text, cap)) {
            return new Draft(null, "Your previous draft was rejected for stating something "
                    + "that is not in the facts given, or for leaving a placeholder in. "
                    + "Use only FACTS YOU MAY USE.");
        }
        List<String> ungrounded = groundingProblems(text, grounding);
        if (!ungrounded.isEmpty()) {
            log.info("Letter rejected as ungrounded: {}", String.join("; ", ungrounded));
            return new Draft(null, "Your previous draft was rejected because "
                    + String.join("; ", ungrounded) + ". Use only FACTS YOU MAY USE; nothing in "
                    + "the job description is his experience.");
        }

        List<String> tells = new ArrayList<>(HumanTone.tells(text));
        tells.addAll(HumanTone.borrowedFromExample(text));
        List<String> structure = HumanTone.structureProblems(text);
        boolean dashes = HumanTone.hasDashes(text);
        if (!tells.isEmpty() || dashes || !structure.isEmpty()) {
            log.info("Draft reads as generated{}{}{}",
                    tells.isEmpty() ? "" : " (" + String.join(", ", tells) + ")",
                    dashes ? " and used a dash" : "",
                    structure.isEmpty() ? "" : " [" + String.join("; ", structure) + "]");
            return new Draft(null, HumanTone.retryNote(tells, dashes, structure));
        }
        return new Draft(text, null);
    }

    private String systemPrompt(int cap) {
        return """
                You write short job-application cover letters for one specific person.

                The message has three parts, and they are different kinds of thing.
                JOB DESCRIPTION is what the employer wants. Nothing in it is about him.
                WHAT HE CANNOT CLAIM lists what this posting asks for that his evidence
                does not support. FACTS YOU MAY USE is his approved evidence, and the
                only facts about him that exist.

                Absolute rules, in order of importance:
                1. Every statement about him comes from FACTS YOU MAY USE. You may
                   rephrase. You may not add, extrapolate or round anything.
                2. Never present anything that appears only in the JOB DESCRIPTION or
                   under WHAT HE CANNOT CLAIM as his experience. Where a related fact is
                   given you may name that instead; otherwise leave the point out.
                3. Keep every figure exactly as written, including "approximately" and
                   "roughly". Keep every phrase marked "keep".
                4. Name no technology, employer, project, product or person that is not
                   in FACTS YOU MAY USE, other than the company you are writing to.
                5. Never state a number of years of experience. Never use a placeholder
                   such as [Company] or {{role}}. No flattery, and no claims about the
                   company.
                6. Pick AT MOST TWO facts. One must connect to something THIS posting
                   actually asks for, and you must say which.

                %s
                Format: exactly three paragraphs separated by a blank line. No
                greeting, no sign-off. Under %d characters and under 200 words.

                  Paragraph 1: the most relevant thing he has built, and what was
                                hard about it. Do not open with "I".
                  Paragraph 2: how that bears on this specific role.
                  Paragraph 3: one or two sentences, what he is looking for.

                %s
                Output the letter only.
                """.formatted(HumanTone.styleRules(), cap, HumanTone.letterExample());
    }

    private static String userPrompt(Posting posting, String company, List<EvidenceUse> facts,
            ApplicationEvidenceContext evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("JOB DESCRIPTION - what the employer is asking for. None of it is about him.\n")
                .append("Company: ").append(company).append('\n')
                .append("Role: ").append(posting.getTitle()).append('\n')
                .append("Description (truncated):\n")
                .append(truncate(nullSafe(posting.getDescriptionText()), 4000))
                .append("\n\n");

        List<RequirementEvidence> cannot = evidence.requirements().stream()
                .filter(r -> !r.claimable() && r.importance() != RequirementImportance.SIGNAL)
                .limit(10)
                .toList();
        if (!cannot.isEmpty()) {
            prompt.append("WHAT HE CANNOT CLAIM for this posting\n");
            for (RequirementEvidence requirement : cannot) {
                prompt.append("- ").append(requirement.requirement()).append(": ")
                        .append(cannotLine(requirement)).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("FACTS YOU MAY USE - his approved evidence, and the only facts about him\n");
        for (EvidenceUse use : facts) {
            prompt.append('[').append(use.id()).append("] ").append(sourceLine(use.source()))
                    .append('\n').append("  ").append(use.wording()).append('\n');
            if (!use.item().technologies().isEmpty()) {
                prompt.append("  technologies: ").append(String.join(", ", use.item().technologies()))
                        .append('\n');
            }
            if (!use.item().metrics().isEmpty()) {
                prompt.append("  figures, exactly as written: ")
                        .append(String.join("; ", use.item().metrics())).append('\n');
            }
            if (!use.item().qualifiers().isEmpty()) {
                prompt.append("  keep: \"").append(String.join("\", \"", use.item().qualifiers()))
                        .append("\"\n");
            }
            if (!use.supports().isEmpty()) {
                prompt.append("  bears on: ").append(String.join(", ", use.supports())).append('\n');
            }
        }
        return prompt.toString();
    }

    private static String cannotLine(RequirementEvidence requirement) {
        if (requirement.level() == ExperienceLevel.DIRECT) {
            return "in his skills list only; no evidence shows it in use. Do not claim it.";
        }
        if (!requirement.relatedVia().isEmpty()) {
            return "not his. Related evidence: " + String.join(", ", requirement.relatedVia())
                    + " - you may name that instead, never " + requirement.requirement() + ".";
        }
        return "nothing on record. Do not mention it.";
    }

    private static String sourceLine(EvidenceSource source) {
        if (source == null) {
            return "";
        }
        return source.name() + (source.kind() == EvidenceSource.Kind.EMPLOYMENT ? " (work)" : " (project)");
    }

    /**
     * The fallback, and the floor on quality.
     *
     * <p>Assembled from the approved wordings printed on this application's resume, so
     * it is always safe to send. It reads like a template because it is one - which is
     * still better than an invented claim, and better than an empty box.
     */
    static String template(Posting posting, String company, List<EvidenceUse> facts,
            ApplicationEvidenceContext evidence) {
        StringBuilder letter = new StringBuilder();
        letter.append("I am applying for the ").append(posting.getTitle())
                .append(" role at ").append(company).append(".\n\n")
                .append("The work of mine most relevant to it:\n");
        List<EvidenceUse> lead = facts.subList(0, Math.min(2, facts.size()));
        for (EvidenceUse use : lead) {
            String wording = use.wording().strip();
            letter.append("- ").append(wording).append(wording.endsWith(".") ? "" : ".").append('\n');
        }
        List<String> leadIds = lead.stream().map(EvidenceUse::id).toList();
        List<String> overlap = evidence.requirements().stream()
                .filter(RequirementEvidence::claimable)
                .filter(r -> r.claimableIds().stream().anyMatch(leadIds::contains))
                .map(RequirementEvidence::display)
                .distinct()
                .limit(4)
                .toList();
        letter.append('\n');
        if (!overlap.isEmpty()) {
            letter.append("The overlap with what the role asks for is ")
                    .append(joinNaturally(overlap)).append(". I would be glad to talk it through.");
        } else {
            letter.append("I would be glad to talk it through.");
        }
        return letter.toString();
    }

    /**
     * Rejects a letter rather than editing it, on the model's habits alone. Grounding
     * is {@link #groundingProblems}.
     *
     * @return true if the text is safe to send
     */
    static boolean validate(String letter, int cap) {
        if (letter == null || letter.isBlank()) {
            return false;
        }
        if (letter.length() > cap) {
            log.info("Letter rejected: {} chars over the {} cap", letter.length() - cap, cap);
            return false;
        }
        String lower = letter.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN) {
            if (lower.contains(phrase)) {
                log.info("Letter rejected: contains '{}'", phrase);
                return false;
            }
        }
        // A model that ignored "no sign-off" has usually ignored the other rules
        // too, so this is treated as a signal rather than tidied away.
        return !lower.contains("sincerely,") && !lower.contains("yours faithfully");
    }

    /**
     * Strips the wrapping models add, then removes dash punctuation.
     */
    static String clean(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        if (cleaned.length() > 1 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return HumanTone.removeDashes(cleaned);
    }

    private static String joinNaturally(List<String> items) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
