package com.anuragbhandary.jobradar.knowledge.ai;

import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch;
import com.anuragbhandary.jobradar.evidence.EvidenceReadiness;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.evidence.GroundedProse;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.experience.ClaimValidator;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.knowledge.experience.Positioning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drafts an answer to a career question, and checks what it drafted.
 *
 * <p>Only reached when the deterministic path has already failed: nothing here
 * runs while a profile fact, an approved rule or a derivation could answer the
 * question, and nothing here is ever asked about a work permit, a salary, a notice
 * period or anything else that is a fact about him rather than about his work - the
 * concept's category keeps those out before they arrive.
 *
 * <h2>The evidence is the evidence bank</h2>
 * Both routes draw their facts from evidence-bank items, cited by their ids:
 * <ul>
 *   <li>{@link #propose} - an open-ended question (why this company, why this role).
 *       The model is given the application's strongest evidence and must cite the
 *       ids it used.</li>
 *   <li>{@link #positioned} - "describe your experience with Kafka". What may be
 *       claimed was decided in Java by the positioner; the model is given the bank
 *       items behind that decision and asked only to phrase it. A technology that is
 *       only in his skills list, with no item showing it in use, is not drafted at
 *       all - that answer is his to give.</li>
 * </ul>
 *
 * <h2>Checks, all of them</h2>
 * Citations must exist; {@link ClaimValidator} for a positioned answer (the
 * disclaimer, no claim of the subject, no inflation); {@link GroundedProse} against
 * the items supplied (technologies, figures and hedges, qualifiers, names); and the
 * {@link HumanTone} rules. Any failure is {@link ProposedAnswer.Status#REJECTED}
 * with the reason attached, and the reason is shown instead of the draft.
 */
@Component
public class AnswerProposer {

    private static final Logger log = LoggerFactory.getLogger(AnswerProposer.class);

    /** Items given for an open-ended answer. */
    static final int MATERIAL_ITEMS = 6;
    /** Items behind a positioned answer. */
    static final int GROUNDING_ITEMS = 3;

    private final LlmClient llm;
    private final EvidenceBank bank;
    private final EvidenceReadiness readiness;
    private final ObjectMapper json;
    /** Used by ClaimValidator's own checks; the technologies a draft may name are narrower. */
    private final ExperienceIndex index;

    public AnswerProposer(LlmClient llm, EvidenceBank bank, EvidenceReadiness readiness,
            ObjectMapper json, ExperienceIndex index) {
        this.llm = llm;
        this.bank = bank;
        this.readiness = readiness;
        this.json = json;
        this.index = index;
    }

    public boolean isUsable() {
        return llm.isUsable();
    }

    public Optional<ProposedAnswer> propose(Concept concept, ApplicationContext context,
            String questionLabel) {
        return propose(concept, context, questionLabel, null);
    }

    /**
     * @param evidence the application's evidence context, when there is one. Without
     *                 it the bank's items are used in file order.
     * @return empty when there is no model configured or the concept is not open to
     *         drafting. A {@link ProposedAnswer} otherwise - which may still be
     *         NOTHING or REJECTED, and both are results rather than failures.
     */
    public Optional<ProposedAnswer> propose(Concept concept, ApplicationContext context,
            String questionLabel, ApplicationEvidenceContext evidence) {

        if (concept == null || !concept.aiEligible() || !llm.isUsable()) {
            return Optional.empty();
        }
        List<String> blockers = readiness.blockers();
        if (!blockers.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "the evidence bank is unavailable, so there is nothing safe to draft from: "
                            + blockers.getFirst()));
        }
        CandidateMaterial material = evidence != null
                ? CandidateMaterial.of(evidence, MATERIAL_ITEMS)
                : CandidateMaterial.of(bank, MATERIAL_ITEMS);
        if (material.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "there is no material to answer from"));
        }

        Optional<String> raw = llm.complete(system(concept), user(concept, context,
                questionLabel, material));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(validate(concept, raw.get(), material, allowedNames(context)));
    }

    /**
     * Drafts an answer to a technology question from a decided position, grounded on
     * the evidence-bank items behind that position.
     *
     * <p>DIRECT is drafted from the items that show the technology in use; ADJACENT
     * and CONCEPTUAL from the items carrying the related technology, with the
     * disclaimer the positioning requires. DIRECT with no such item - a skill he
     * lists and has no evidence for - is refused: that answer is his to give.
     */
    public Optional<ProposedAnswer> positioned(Concept concept, Positioning positioning,
            ApplicationContext context, String questionLabel) {

        if (positioning == null || !llm.isUsable()) {
            return Optional.empty();
        }
        List<String> blockers = readiness.blockers();
        if (!blockers.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "the evidence bank is unavailable, so there is nothing safe to draft from: "
                            + blockers.getFirst()));
        }
        List<EvidenceMatch> grounding = grounding(positioning);
        if (positioning.isDirect() && grounding.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(), positioning.subject()
                    + " is only in your skills list or tags; no evidence item shows you using it, "
                    + "so this one needs your own answer"));
        }

        Optional<String> raw = llm.complete(positioningSystem(positioning),
                positioningUser(positioning, grounding, context, questionLabel));
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String answer = HumanTone.removeDashes(strip(raw.get()).trim());
        if (answer.isBlank()) {
            return Optional.of(ProposedAnswer.nothing(concept.id()));
        }

        ClaimValidator.Verdict verdict = ClaimValidator.check(answer, positioning, index);
        if (!verdict.acceptable()) {
            log.info("Rejected a positioned draft for '{}': {}",
                    positioning.subject(), verdict.problem());
            return Optional.of(ProposedAnswer.rejected(concept.id(), verdict.problem()));
        }
        List<String> names = new ArrayList<>(allowedNames(context));
        names.add(positioning.subject());
        names.addAll(positioning.via());
        grounding.forEach(match -> {
            if (match.source() != null) {
                names.add(match.source().name());
            }
        });
        List<String> ungrounded = GroundedProse.problems(answer, new GroundedProse.Grounding(
                grounding.stream().map(EvidenceMatch::item).toList(), names, List.of()));
        if (!ungrounded.isEmpty()) {
            log.info("Rejected a positioned draft for '{}': {}", positioning.subject(), ungrounded);
            return Optional.of(ProposedAnswer.rejected(concept.id(), ungrounded.getFirst()));
        }
        List<String> tells = HumanTone.tells(answer);
        if (!tells.isEmpty()) {
            return Optional.of(ProposedAnswer.rejected(concept.id(),
                    "it reads as generated: " + String.join(", ", tells)));
        }
        return Optional.of(new ProposedAnswer(ProposedAnswer.Status.PROPOSED, concept.id(),
                answer, grounding.stream().map(EvidenceMatch::itemId).toList(), Confidence.MEDIUM,
                null));
    }

    /**
     * The bank items behind a positioning: those showing the subject in use for
     * DIRECT, those carrying the related technology for ADJACENT and CONCEPTUAL, and
     * none at all below that - nothing specific connects, and offering something
     * anyway is reaching.
     */
    List<EvidenceMatch> grounding(Positioning positioning) {
        Map<String, EvidenceMatch> found = new LinkedHashMap<>();
        if (positioning.level() == ExperienceLevel.DIRECT) {
            bank.strongestFor(positioning.subject()).stream().filter(EvidenceMatch::supportsClaim)
                    .forEach(m -> found.putIfAbsent(m.itemId(), m));
        } else if (positioning.level() == ExperienceLevel.ADJACENT
                || positioning.level() == ExperienceLevel.CONCEPTUAL) {
            for (String via : positioning.via()) {
                bank.strongestFor(via).stream().filter(EvidenceMatch::supportsClaim)
                        .forEach(m -> found.putIfAbsent(m.itemId(), m));
            }
        }
        return found.values().stream().limit(GROUNDING_ITEMS).toList();
    }

    /**
     * The instructions for a positioned answer.
     *
     * <p>The permitted and forbidden claims are pasted in from the positioning rather
     * than described in general terms: "you may not say you have used Kubernetes" is
     * an instruction, and "do not exaggerate" is only advice.
     */
    private static String positioningSystem(Positioning positioning) {
        return """
                You write one short answer to one question on a job application
                form, in the applicant's own voice. He will read it before it is
                sent and has to be able to defend every word in an interview.

                What is true here has already been decided from his evidence. You are
                deciding how to say it, and nothing else. Every fact about him must
                come from HIS EVIDENCE below; keep every figure exactly as written and
                every phrase marked "keep".

                THE POSITION
                %s

                HOW TO FRAME IT
                %s

                YOU MAY SAY
                %s

                YOU MAY NOT SAY
                %s

                Two or three sentences. Plain first person. Do not open with "As a"
                or "I am excited". Do not name a technology, employer or project that
                is not in his evidence. Do not repeat the question back.

                %s
                """.formatted(
                        positioning.describe(),
                        positioning.framing(),
                        bullets(positioning.mayClaim()),
                        bullets(positioning.mustNotSay()),
                        HumanTone.styleRules());
    }

    private static String positioningUser(Positioning positioning, List<EvidenceMatch> grounding,
            ApplicationContext context, String questionLabel) {

        StringBuilder out = new StringBuilder("QUESTION\n")
                .append(questionLabel == null ? "Do you have experience with "
                        + positioning.subject() + "?" : questionLabel)
                .append("\n\nASKED ABOUT\n").append(positioning.subject())
                .append(positioning.isDirect() ? "\n\nHIS EVIDENCE\n"
                        : "\n\nHIS EVIDENCE - related work of his, not " + positioning.subject() + "\n");
        if (grounding.isEmpty()) {
            out.append("(none on record for this)\n");
        } else {
            for (EvidenceMatch match : grounding) {
                EvidenceItem item = match.item();
                out.append('[').append(item.id()).append("] ").append(where(match.source()))
                        .append(item.claim()).append('\n');
                if (!item.metrics().isEmpty()) {
                    out.append("    figures, exactly as written: ")
                            .append(String.join("; ", item.metrics())).append('\n');
                }
                if (!item.qualifiers().isEmpty()) {
                    out.append("    keep: \"").append(String.join("\", \"", item.qualifiers()))
                            .append("\"\n");
                }
            }
        }
        if (context != null) {
            out.append("\nCONTEXT\n").append(context.describe()).append('\n');
        }
        return out.toString();
    }

    private static String where(EvidenceSource source) {
        if (source == null) {
            return "";
        }
        return source.name() + (source.kind() == EvidenceSource.Kind.EMPLOYMENT ? " (work): " : " (project): ");
    }

    private static String bullets(List<String> items) {
        StringBuilder out = new StringBuilder();
        items.forEach(item -> out.append("- ").append(item).append('\n'));
        return out.toString();
    }

    /** Turns confirmed citations into evidence pointing at evidence-bank items by id. */
    public List<Evidence> evidenceFor(ProposedAnswer proposal) {
        List<Evidence> evidence = new ArrayList<>();
        for (String ref : proposal.evidenceRefs()) {
            bank.find(ref).ifPresent(item -> evidence.add(
                    Evidence.evidenceItem(item.id(), shorten(item.claim()))));
        }
        return List.copyOf(evidence);
    }

    // ------------------------------------------------------------------

    private static String system(Concept concept) {
        return """
                You draft one answer to one question on a job application form,
                for the person whose evidence is given below. He will read it
                before it is sent and has to be able to defend every word of it.

                Reply with JSON only, in exactly this shape:

                {"status":"PROPOSED","concept":"%s","answer":"...",
                 "evidence":["an-item-id"],"confidence":"MEDIUM"}

                Rules, in order of importance:
                1. Use only HIS EVIDENCE below. Do not name a technology, an
                   employer, a project, a metric or a date that does not appear in
                   it. Every claim must come from an item you cite. Keep every
                   figure exactly as written and every phrase marked "keep".
                2. "evidence" lists the ids of the items you used, exactly as they
                   are written between the brackets. An answer you cannot cite is
                   an answer you must not give.
                3. Never state a number of years of experience.
                4. If the evidence does not support an answer, reply with
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

    /** Parse, then the checks. Any failure is a REJECTED with its reason. */
    private ProposedAnswer validate(Concept concept, String raw, CandidateMaterial material,
            List<String> allowedNames) {
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
        node.path("evidence").forEach(ref -> refs.add(ref.asText("").trim()));

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

        // 2. It rests on the evidence it was given.
        List<String> ungrounded = GroundedProse.problems(answer,
                material.grounding(allowedNames, List.of()));
        if (!ungrounded.isEmpty()) {
            log.info("Rejected a draft for {}: {}", concept.id(), ungrounded);
            return ProposedAnswer.rejected(concept.id(), ungrounded.getFirst());
        }

        // 3. It must not read as generated.
        List<String> tells = HumanTone.tells(answer);
        if (!tells.isEmpty()) {
            return ProposedAnswer.rejected(concept.id(),
                    "it reads as generated: " + String.join(", ", tells));
        }

        return new ProposedAnswer(ProposedAnswer.Status.PROPOSED, concept.id(), answer,
                List.copyOf(refs.stream().filter(ref -> !ref.isBlank())
                        .map(ref -> ref.replace("[", "").replace("]", "").trim()).toList()),
                confidence(node.path("confidence").asText("MEDIUM")), null);
    }

    private static List<String> allowedNames(ApplicationContext context) {
        List<String> names = new ArrayList<>();
        if (context != null) {
            if (context.company() != null) {
                names.add(context.company());
            }
            if (context.roleTitle() != null) {
                names.add(context.roleTitle());
            }
        }
        return names;
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

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 159) + "…";
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
