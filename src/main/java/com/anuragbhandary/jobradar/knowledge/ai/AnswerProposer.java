package com.anuragbhandary.jobradar.knowledge.ai;

import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.experience.ClaimValidator;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.Positioning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drafts an answer to an open-ended question, and checks what it drafted.
 *
 * <p>Only reached when the deterministic path has already failed: nothing here
 * runs while a profile fact, an approved rule or a derivation could answer the
 * question. That is a correctness rule before it is a cost one - a model asked
 * for a notice period will produce a confident, plausible, wrong one.
 *
 * <h2>Three checks, in order of how much they catch</h2>
 * <ol>
 *   <li><b>Citations must exist.</b> The model is given numbered material and
 *       has to say which items it used. A reference to an id that was never
 *       supplied is a fabrication with a receipt.</li>
 *   <li><b>Technologies must be his.</b> Every term the draft names is run
 *       through the same vocabulary the resume is, so a paragraph about
 *       Kubernetes is rejected when the resume has never said Kubernetes. This
 *       is the check the phrase blocklists could not do: a fluent invention
 *       passes every one of them.</li>
 *   <li><b>It must not read as generated.</b> The existing {@link HumanTone}
 *       rules, unchanged.</li>
 * </ol>
 *
 * <p>All three produce {@link ProposedAnswer.Status#REJECTED} with the reason
 * attached. A rejected draft is never shown as an answer, and the reason is
 * shown instead - which is how the checks earn their keep rather than looking
 * like the model failing silently.
 */
@Component
public class AnswerProposer {

    private static final Logger log = LoggerFactory.getLogger(AnswerProposer.class);

    private final LlmClient llm;
    private final ResumeModel resume;
    private final ObjectMapper json;
    /** Everything his resume names, so a draft may cite any of it and nothing else. */
    private final ExperienceIndex index;

    public AnswerProposer(LlmClient llm, ResumeModel resume, ObjectMapper json,
            ExperienceIndex index) {
        this.llm = llm;
        this.resume = resume;
        this.json = json;
        this.index = index;
    }

    public boolean isUsable() {
        return llm.isUsable();
    }

    /**
     * @return empty when there is no model configured or the concept is not open
     *         to drafting. A {@link ProposedAnswer} otherwise - which may still
     *         be NOTHING or REJECTED, and both are results rather than failures.
     */
    public Optional<ProposedAnswer> propose(Concept concept, ApplicationContext context,
            String questionLabel) {

        if (concept == null || !concept.aiEligible() || !llm.isUsable()) {
            return Optional.empty();
        }
        CandidateMaterial material = CandidateMaterial.of(resume);
        if (material.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "there is no material to answer from"));
        }

        Optional<String> raw = llm.complete(system(concept), user(concept, context,
                questionLabel, material));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(validate(concept, raw.get(), material));
    }

    /**
     * Drafts an answer to a technology question from a decided position.
     *
     * <p>Different from {@link #propose} in the one way that matters: what may be
     * claimed was settled before this was called, in Java, from his resume. The
     * model is not asked whether he knows Kubernetes. It is told he does not, told
     * which of his own technologies it may name, and asked to write two sentences.
     *
     * <p>Then {@link ClaimValidator} reads what came back and checks it against
     * the same positioning - because the prompt is a request and the validator is
     * the rule. A draft that says he deployed Kubernetes clusters is refused here
     * however politely it was asked not to.
     */
    public Optional<ProposedAnswer> positioned(Concept concept, Positioning positioning,
            ApplicationContext context, String questionLabel) {

        if (positioning == null || !llm.isUsable()) {
            return Optional.empty();
        }
        Optional<String> raw = llm.complete(positioningSystem(positioning),
                positioningUser(positioning, context, questionLabel));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String answer = HumanTone.removeDashes(strip(raw.get()).trim());
        // The model was asked for prose, not JSON: there is nothing to decide, so
        // there is no structure to return. Everything that would have been in the
        // JSON - what is claimed, what it is based on - is already in the
        // positioning that produced it.
        if (answer.isBlank()) {
            return Optional.of(ProposedAnswer.nothing(concept.id()));
        }

        ClaimValidator.Verdict verdict = ClaimValidator.check(answer, positioning, index);
        if (!verdict.acceptable()) {
            log.info("Rejected a positioned draft for '{}': {}",
                    positioning.subject(), verdict.problem());
            return Optional.of(ProposedAnswer.rejected(concept.id(), verdict.problem()));
        }
        List<String> tells = HumanTone.tells(answer);
        if (!tells.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "it reads as generated: " + String.join(", ", tells)));
        }
        return Optional.of(new ProposedAnswer(ProposedAnswer.Status.PROPOSED, concept.id(),
                answer, positioning.namedEvidence(), Confidence.MEDIUM, null));
    }

    /**
     * The instructions for a positioned answer.
     *
     * <p>The permitted and forbidden claims are pasted in from the positioning
     * rather than described in general terms. "Do not exaggerate" is advice; "you
     * may not say you have used Kubernetes" is an instruction, and the difference
     * shows in what comes back.
     */
    private static String positioningSystem(Positioning positioning) {
        return """
                You write one short answer to one question on a job application
                form, in the applicant's own voice. He will read it before it is
                sent and has to be able to defend every word in an interview.

                What is true here has already been decided from his resume. You are
                deciding how to say it, and nothing else.

                THE POSITION
                %s

                HOW TO FRAME IT
                %s

                YOU MAY SAY
                %s

                YOU MAY NOT SAY
                %s

                Two or three sentences. Plain first person. Do not open with "As a"
                or "I am excited". Do not list technologies he was not given. Do not
                repeat the question back.

                %s
                """.formatted(
                        positioning.describe(),
                        positioning.framing(),
                        bullets(positioning.mayClaim()),
                        bullets(positioning.mustNotSay()),
                        HumanTone.styleRules());
    }

    /**
     * The evidence, with where each piece came from.
     *
     * <p>Depth is included because it changes the sentence: "used at work"
     * supports a different clause from "in a personal project", and a model given
     * only the names writes the stronger one every time.
     */
    private static String positioningUser(Positioning positioning,
            ApplicationContext context, String questionLabel) {

        StringBuilder out = new StringBuilder("QUESTION\n")
                .append(questionLabel == null ? "Do you have experience with "
                        + positioning.subject() + "?" : questionLabel)
                .append("\n\nASKED ABOUT\n").append(positioning.subject())
                .append("\n\nHIS RELEVANT EXPERIENCE\n");
        if (positioning.evidence().isEmpty()) {
            out.append("(none on record for this)\n");
        } else {
            positioning.evidence().forEach(entry -> entry.evidence().stream()
                    .limit(2)
                    .forEach(item -> out.append("- ").append(item.describe()).append('\n')));
        }
        if (context != null) {
            out.append("\nCONTEXT\n").append(context.describe()).append('\n');
        }
        return out.toString();
    }

    private static String bullets(List<String> items) {
        StringBuilder out = new StringBuilder();
        items.forEach(item -> out.append("- ").append(item).append('\n'));
        return out.toString();
    }

    /** Turns citations into evidence, once they have been confirmed to exist. */
    public List<Evidence> evidenceFor(ProposedAnswer proposal) {
        CandidateMaterial material = CandidateMaterial.of(resume);
        List<Evidence> evidence = new ArrayList<>();
        for (String ref : proposal.evidenceRefs()) {
            if (material.hasItem(ref)) {
                evidence.add(Evidence.resume(ref, material.item(ref)));
            }
        }
        return List.copyOf(evidence);
    }

    // ------------------------------------------------------------------

    private static String system(Concept concept) {
        return """
                You draft one answer to one question on a job application form,
                for the person whose material is given below. He will read it
                before it is sent and has to be able to defend every word of it.

                Reply with JSON only, in exactly this shape:

                {"status":"PROPOSED","concept":"%s","answer":"...",
                 "evidence":["R1","P2"],"confidence":"MEDIUM"}

                Rules, in order of importance:
                1. Use only the material below. Do not name a technology, an
                   employer, a project, a metric or a date that does not appear in
                   it. Every claim must come from an item you cite.
                2. "evidence" lists the ids of the items you used. Cite only ids
                   that appear in the material. An answer you cannot cite is an
                   answer you must not give.
                3. Never state a number of years of experience.
                4. If the material does not support an answer, reply with
                   {"status":"NOTHING"} and nothing else. A blank on a form is
                   recoverable and a false claim is not.
                5. Two or three sentences unless the question obviously wants one
                   word.

                %s
                """.formatted(concept.id(), HumanTone.styleRules());
    }

    private static String user(Concept concept, ApplicationContext context,
            String questionLabel, CandidateMaterial material) {
        StringBuilder out = new StringBuilder("QUESTION\n")
                .append(questionLabel == null ? concept.label() : questionLabel)
                .append("\n\nCONTEXT\n");
        if (context != null) {
            out.append(context.describe()).append('\n');
        }
        return out.append('\n').append(material.prompt()).toString();
    }

    /** Parse, then the three checks. Any failure is a REJECTED with its reason. */
    private ProposedAnswer validate(Concept concept, String raw, CandidateMaterial material) {
        JsonNode node;
        try {
            node = json.readTree(strip(raw));
        } catch (Exception e) {
            // Not JSON at all. Rejected rather than salvaged: a model that
            // ignored the output contract has ignored the rules with it.
            return ProposedAnswer.rejected(concept.id(),
                    "the model did not return the requested JSON");
        }

        String status = node.path("status").asText("").toUpperCase(Locale.ROOT);
        if ("NOTHING".equals(status)) {
            return ProposedAnswer.nothing(concept.id());
        }
        String answer = HumanTone.removeDashes(node.path("answer").asText("").trim());
        if (answer.isBlank()) {
            return ProposedAnswer.nothing(concept.id());
        }

        List<String> refs = new ArrayList<>();
        node.path("evidence").forEach(ref -> refs.add(ref.asText("")));

        // 1. Citations must exist.
        List<String> invented = refs.stream()
                .filter(ref -> !ref.isBlank() && !material.hasItem(ref))
                .toList();
        if (!invented.isEmpty()) {
            log.info("Rejected a draft for {}: cited {} which is not in the material",
                    concept.id(), invented);
            return ProposedAnswer.rejected(concept.id(),
                    "it cited material that does not exist: " + String.join(", ", invented));
        }
        if (refs.stream().allMatch(String::isBlank)) {
            return ProposedAnswer.rejected(concept.id(),
                    "it cited nothing, so nothing in it can be checked");
        }

        // 2. Technologies must be his.
        List<String> unsupported = material.unsupportedTechnologies(answer);
        if (!unsupported.isEmpty()) {
            log.info("Rejected a draft for {}: names {} which the resume does not",
                    concept.id(), unsupported);
            return ProposedAnswer.rejected(concept.id(),
                    "it claims experience with " + String.join(", ", unsupported)
                            + ", which your resume does not mention");
        }

        // 3. It must not read as generated.
        List<String> tells = HumanTone.tells(answer);
        if (!tells.isEmpty()) {
            return ProposedAnswer.rejected(concept.id(),
                    "it reads as generated: " + String.join(", ", tells));
        }

        return new ProposedAnswer(ProposedAnswer.Status.PROPOSED, concept.id(), answer,
                List.copyOf(refs.stream().filter(ref -> !ref.isBlank()).toList()),
                confidence(node.path("confidence").asText("MEDIUM")), null);
    }

    private static Confidence confidence(String raw) {
        try {
            // Never HIGH, whatever the model says about itself. A drafted answer
            // is read before it is used, and a model's own confidence is not
            // evidence of anything.
            Confidence stated = Confidence.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return stated == Confidence.HIGH ? Confidence.MEDIUM : stated;
        } catch (IllegalArgumentException e) {
            return Confidence.LOW;
        }
    }

    private static String strip(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        int open = cleaned.indexOf('{');
        int close = cleaned.lastIndexOf('}');
        return open >= 0 && close > open ? cleaned.substring(open, close + 1) : cleaned;
    }
}
