package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationEvidence;
import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
import com.anuragbhandary.jobradar.evidence.GroundedProse;
import com.anuragbhandary.jobradar.knowledge.ai.CandidateMaterial;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The assistant behind the preparation screen.
 *
 * <p>Three jobs: draft an answer to an application question, rewrite the cover
 * letter, and answer a question about the posting.
 *
 * <p>The first two produce text that will be sent to an employer under the
 * applicant's name, so they are grounded on the posting's evidence context - the same
 * evidence-bank selection its resume is printed from - and checked with
 * {@link GroundedProse} as well as the {@link HumanTone} rules. A draft that is not
 * grounded is refused with the reason, never returned. The third is for the applicant
 * to read and goes nowhere; it may compare the posting with his evidence and with the
 * skills he lists, labelled as a list rather than as evidence.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final int MATERIAL_ITEMS = 6;

    private final LlmClient llm;
    private final ApplicationEvidence evidence;
    private final ResumeModel resume;

    public AssistantService(LlmClient llm, ApplicationEvidence evidence, ResumeModel resume) {
        this.llm = llm;
        this.evidence = evidence;
        this.resume = resume;
    }

    public boolean isUsable() {
        return llm.isUsable();
    }

    /**
     * @param text     what to use, or null when nothing acceptable came back
     * @param rejected why a draft was thrown away, for the page to show
     */
    public record Draft(String text, String rejected) {

        public boolean ok() {
            return text != null && !text.isBlank();
        }

        static Draft of(String text) {
            return new Draft(text, null);
        }

        static Draft none() {
            return new Draft(null, null);
        }

        static Draft refused(String why) {
            return new Draft(null, why);
        }
    }

    /**
     * Drafts an answer to one application question, from this posting's evidence.
     *
     * <p>An answer it cannot support must come back as the single word NOTHING rather
     * than as a plausible sentence.
     */
    public Draft answerQuestion(Posting posting, String company, String question) {
        if (!llm.isUsable() || question == null || question.isBlank()) {
            return Draft.none();
        }
        Optional<ApplicationEvidenceContext> context = contextFor(posting);
        if (context.isEmpty()) {
            return Draft.refused(unavailable);
        }
        CandidateMaterial material = CandidateMaterial.of(context.get(), MATERIAL_ITEMS);

        String system = """
                You are drafting one answer to one question on a job application
                form, for the person whose evidence is given below. He will read it
                before it is sent, and he has to be able to defend every word of it in
                an interview.

                Rules:
                1. Use only HIS EVIDENCE. Do not invent projects, technologies,
                   employers, numbers or dates. Keep figures exactly as written and
                   every phrase marked "keep".
                2. Never state a number of years of experience.
                3. Answer the question that was asked. Two or three sentences
                   unless it obviously wants one word.
                4. If the evidence does not support an answer, reply with exactly:
                   NOTHING
                   Do not write a plausible answer instead.

                %s
                Output the answer only, with no preamble.
                """.formatted(HumanTone.styleRules());

        String user = "QUESTION\n" + question
                + "\n\nCONTEXT\nCompany: " + company
                + "\nRole: " + posting.getTitle()
                + "\n\n" + material.prompt();

        return grounded(policed(system, user, "answer"), material.grounding(
                List.of(nullSafe(company), nullSafe(posting.getTitle())), List.of()));
    }

    /** Rewrites the letter, optionally against a steer typed on the page. */
    public Draft rewriteLetter(
            Posting posting, String company, String current, String instruction) {

        if (!llm.isUsable()) {
            return Draft.none();
        }
        Optional<ApplicationEvidenceContext> context = contextFor(posting);
        if (context.isEmpty()) {
            return Draft.refused(unavailable);
        }
        CandidateMaterial material = CandidateMaterial.of(context.get(), MATERIAL_ITEMS);

        String system = """
                You rewrite a job-application cover letter. Same facts, better
                writing. You may drop a sentence, you may not add a claim.

                Rules:
                1. Only facts from HIS EVIDENCE below. The posting describes what the
                   employer wants, never his experience. Keep figures exactly as
                   written and every phrase marked "keep".
                2. Never state a number of years of experience.
                3. No placeholders such as [Company].
                4. Pick at most two things he has built.
                5. Exactly three paragraphs separated by a blank line, no greeting,
                   no sign-off, under 1800 characters and under 200 words. The
                   first paragraph must not start with "I". The second must say how
                   the work bears on this specific role.

                %s
                %s
                Output the letter only.
                """.formatted(HumanTone.styleRules(), HumanTone.letterExample());

        StringBuilder user = new StringBuilder();
        if (instruction != null && !instruction.isBlank()) {
            user.append("WHAT HE WANTS CHANGED\n").append(instruction.trim()).append("\n\n");
        }
        if (current != null && !current.isBlank()) {
            user.append("CURRENT LETTER\n").append(current).append("\n\n");
        }
        user.append("POSTING - what the employer wants, not his experience\nCompany: ").append(company)
                .append("\nRole: ").append(posting.getTitle())
                .append("\nDescription:\n").append(truncate(nullSafe(posting.getDescriptionText()), 4000))
                .append("\n\n").append(material.prompt());

        return grounded(policed(system, user.toString(), "letter"), material.grounding(
                List.of(nullSafe(company), nullSafe(posting.getTitle())),
                context.get().notClaimable()));
    }

    /**
     * Answers a question about the posting, for the applicant to read.
     *
     * <p>Not policed for tone or grounding, because nothing here is sent anywhere.
     */
    public Draft ask(Posting posting, String company, String question) {
        if (!llm.isUsable() || question == null || question.isBlank()) {
            return Draft.none();
        }

        String system = """
                You answer questions about a job posting for the candidate who is
                about to apply to it. You have the posting text and his background.

                Two different kinds of question, and they are answered differently.

                A question of FACT about the role or company is answered from the
                posting. If the posting does not say, say that it does not say. Do
                not guess at a salary, a visa policy or a team size.

                A question of JUDGEMENT is answered by comparing the two documents
                you have been given. Name the specific things the posting asks for
                that his evidence does not show, and the specific things it does. A
                skill he only lists is not evidence of having used it; say so where
                it matters.

                Be brief and concrete. Name technologies, not qualities. No dashes
                as punctuation, use commas or full stops.
                """;

        String background = contextFor(posting)
                .map(context -> CandidateMaterial.of(context, MATERIAL_ITEMS).prompt())
                .orElse("HIS EVIDENCE: unavailable (" + unavailable + ")\n");

        String user = "QUESTION\n" + question
                + "\n\nPOSTING\nCompany: " + company
                + "\nRole: " + posting.getTitle()
                + "\nLocation: " + nullSafe(posting.getLocation())
                + "\nStated pay: " + orNone(posting.getSalaryText())
                + "\nSponsorship language: " + orNone(posting.getSponsorshipSignal())
                + "\n\n" + truncate(nullSafe(posting.getDescriptionText()), 8000)
                + "\n\n" + background + skillsListed();

        return llm.complete(system, user)
                .map(HumanTone::removeDashes)
                .map(Draft::of)
                .orElse(Draft.none());
    }

    // ------------------------------------------------------------------

    /** Why the last context could not be built, for the page. */
    private String unavailable = "the evidence bank is unavailable";

    private Optional<ApplicationEvidenceContext> contextFor(Posting posting) {
        try {
            return Optional.of(evidence.forPosting(posting));
        } catch (EvidenceIntegrityException e) {
            unavailable = e.getMessage();
            log.info("No evidence for the assistant: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** A draft that is not grounded on the evidence it was given is refused, with the reason. */
    private static Draft grounded(Draft draft, GroundedProse.Grounding grounding) {
        if (!draft.ok()) {
            return draft;
        }
        List<String> problems = GroundedProse.problems(draft.text(), grounding);
        if (problems.isEmpty()) {
            return draft;
        }
        return Draft.refused("The draft was refused: " + problems.getFirst());
    }

    /** Generate, clean, judge, and give it exactly one more go with the reason. */
    private Draft policed(String system, String user, String what) {
        Optional<String> raw = llm.complete(system, user);
        if (raw.isEmpty()) {
            return Draft.none();
        }

        String text = HumanTone.removeDashes(strip(raw.get()));
        if (isRefusal(text)) {
            return Draft.none();
        }

        List<String> tells = new java.util.ArrayList<>(HumanTone.tells(text));
        if ("letter".equals(what)) {
            tells.addAll(HumanTone.borrowedFromExample(text));
        }
        boolean dashes = HumanTone.hasDashes(text);
        List<String> structure = "letter".equals(what)
                ? HumanTone.structureProblems(text) : List.of();

        if (tells.isEmpty() && !dashes && structure.isEmpty()) {
            return Draft.of(text);
        }

        log.info("First {} draft read as generated: {} {}", what,
                String.join(", ", tells), String.join("; ", structure));
        Optional<String> retry = llm.complete(
                system, user + "\n\n" + HumanTone.retryNote(tells, dashes, structure));
        if (retry.isEmpty()) {
            return Draft.none();
        }

        String second = HumanTone.removeDashes(strip(retry.get()));
        if (isRefusal(second)) {
            return Draft.none();
        }
        String rejected = "first draft used: " + String.join(", ", tells);
        // Returned even when the second draft still has tells: it is shown to a human
        // with the reason attached. Grounding is checked after this, and is not
        // negotiable the same way.
        return new Draft(second, tells.isEmpty() ? null : rejected);
    }

    private String skillsListed() {
        if (resume.skills() == null || resume.skills().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\nSKILLS HE LISTS - a list, not evidence of use\n");
        resume.skills().forEach(group -> out.append("- ").append(group.group()).append(": ")
                .append(String.join(", ", group.items())).append('\n'));
        return out.toString();
    }

    /** The model was told to say NOTHING when it cannot answer honestly. */
    private static boolean isRefusal(String text) {
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase("NOTHING") || trimmed.length() < 3;
    }

    private static String strip(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        return cleaned;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }
}
