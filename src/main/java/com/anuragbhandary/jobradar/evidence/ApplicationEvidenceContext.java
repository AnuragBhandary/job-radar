package com.anuragbhandary.jobradar.evidence;

import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.List;
import java.util.Optional;

/**
 * The evidence one application is prepared from. Planned once, used for everything.
 *
 * <p>What downstream generators ask instead of reading resume text: "what supports
 * this requirement, and may it be claimed?". The resume planner builds it alongside
 * the resume, from the same ledger and the same selection, so the PDF, the cover
 * letter and the career answers of one application rest on the same evidence. A
 * resumed application rebuilds it from the evidence ids its PDF was printed with.
 *
 * @param requirements  what the posting asks for, each with the evidence that may
 *                      support a claim of it and the evidence that is merely related
 * @param evidence      every item that answers something, or is printed on the
 *                      resume - strongest first: printed, then claimable, then by
 *                      relevance, then in the resume's order
 * @param resumeEvidenceIds the ids printed on the resume, in printed order
 */
public record ApplicationEvidenceContext(
        Long postingId,
        String role,
        List<RequirementEvidence> requirements,
        List<EvidenceUse> evidence,
        List<String> resumeEvidenceIds) {

    /**
     * One requirement, and where it stands.
     *
     * @param display       the requirement as his evidence names it ("Kafka"), for prose
     * @param product       a language, framework, database or service - claimable only
     *                      through an item that lists it
     * @param claimableIds  items whose match lets the requirement itself be claimed
     * @param relatedIds    items that are related and do not license the claim - Docker
     *                      for Kubernetes
     * @param relatedVia    the neighbouring technologies behind an ADJACENT or
     *                      CONCEPTUAL verdict
     */
    public record RequirementEvidence(
            String requirement,
            String display,
            RequirementImportance importance,
            ExperienceLevel level,
            boolean product,
            List<String> claimableIds,
            List<String> relatedIds,
            List<String> relatedVia,
            String note) {

        public RequirementEvidence {
            claimableIds = List.copyOf(claimableIds);
            relatedIds = List.copyOf(relatedIds);
            relatedVia = List.copyOf(relatedVia);
        }

        /** Whether a claim of this requirement is supported: DIRECT, with an item behind it. */
        public boolean claimable() {
            return level == ExperienceLevel.DIRECT && !claimableIds.isEmpty();
        }
    }

    /**
     * One evidence item as this application uses it.
     *
     * @param wording   the approved text printed on the resume for it, or its claim
     * @param supports  requirements it directly supports
     * @param relatedTo requirements it is only related to - never to be claimed through it
     */
    public record EvidenceUse(
            EvidenceItem item,
            EvidenceSource source,
            String wording,
            double relevance,
            boolean onResume,
            List<String> supports,
            List<String> relatedTo) {

        public EvidenceUse {
            supports = List.copyOf(supports);
            relatedTo = List.copyOf(relatedTo);
        }

        public String id() {
            return item.id();
        }

        public boolean supportsDirectClaim() {
            return !supports.isEmpty();
        }

        /** Every wording that may be used for it: the claim and its approved variants. */
        public List<String> approvedWordings() {
            return item.approvedTexts();
        }
    }

    public ApplicationEvidenceContext {
        requirements = List.copyOf(requirements);
        evidence = List.copyOf(evidence);
        resumeEvidenceIds = List.copyOf(resumeEvidenceIds);
    }

    public List<String> evidenceIds() {
        return evidence.stream().map(EvidenceUse::id).toList();
    }

    /** The items printed on the resume, strongest first. */
    public List<EvidenceUse> onResume() {
        return evidence.stream().filter(EvidenceUse::onResume).toList();
    }

    /**
     * The strongest printed items. What a cover letter is written from: never an item
     * the resume does not also show, so the letter and the PDF cannot disagree.
     */
    public List<EvidenceUse> strongest(int limit) {
        return onResume().stream().limit(limit).toList();
    }

    public Optional<EvidenceUse> find(String id) {
        return evidence.stream().filter(use -> use.id().equals(id)).findFirst();
    }

    /**
     * Requirements he may not present as his: everything the ledger did not place at
     * DIRECT with an evidence item behind it. Kubernetes when he has Docker; AWS when
     * it is only in his skills list.
     */
    public List<String> notClaimable() {
        return requirements.stream().filter(r -> !r.claimable())
                .map(RequirementEvidence::requirement).distinct().toList();
    }

    public boolean isEmpty() {
        return evidence.isEmpty();
    }
}
