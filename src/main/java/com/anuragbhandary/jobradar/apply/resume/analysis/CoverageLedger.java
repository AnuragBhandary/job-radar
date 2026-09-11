package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a posting asks for, and what his resume can honestly offer against each.
 *
 * <p>An analysis, not a decision. Nothing reads this to change the resume yet;
 * it exists so the next phase can plan from it and so a person can check the
 * plan's inputs first. Every row says which level of evidence exists, which
 * source ids carry it, and what that level licenses on a resume.
 *
 * @param requirementSource "deterministic", or the model whose grounded
 *                          requirements were used
 * @param weightedCoverage  0..1: requirement weight times evidence credit, over
 *                          total requirement weight
 * @param sourcePriority    source id to how much of this posting it answers,
 *                          highest first. The input a future planner selects
 *                          bullets from - importance-weighted, so one required
 *                          technology outweighs a preferred one however often
 *                          the preferred one is mentioned.
 */
public record CoverageLedger(
        Long postingId,
        String title,
        String requirementSource,
        List<Entry> entries,
        double weightedCoverage,
        int requiredDirect,
        int requiredTotal,
        Map<String, Double> sourcePriority) {

    /**
     * What the evidence level licenses on a resume.
     *
     * <p>Kept apart from the level because the level is about him and this is
     * about the page: ADJACENT evidence is real and worth putting forward, and
     * still never licenses the requirement's own name as a skill.
     */
    public enum ResumeUse {
        /** DIRECT. May be named as a skill and in the bullets that carry it. */
        CLAIM,
        /** ADJACENT, CONCEPTUAL, TRANSFERABLE. Put the evidence forward; never name the requirement as his. */
        EMPHASISE_EVIDENCE,
        /** NONE. Leave it out. */
        OMIT
    }

    /** How the evidence was found. */
    public enum MatchedBy {
        /** Through the experience index and the positioner. */
        INDEX,
        /** A bullet uses the same wording the posting asks for. */
        TEXT,
        /** Nothing found. */
        NONE
    }

    public record EvidenceRef(String sourceId, ResumeSources.Kind kind, String where, String text) {
    }

    /**
     * @param via               the neighbouring terms behind an ADJACENT or
     *                          CONCEPTUAL verdict
     * @param candidateSourceIds bullets that share words with the requirement
     *                          without matching it - shown for review, never
     *                          counted as evidence
     */
    public record Entry(
            Requirement requirement,
            ExperienceLevel level,
            MatchedBy matchedBy,
            List<String> via,
            List<EvidenceRef> evidence,
            List<String> candidateSourceIds,
            ResumeUse resumeUse,
            String note) {
    }

    /**
     * How much each level is worth.
     *
     * <p>Below half for everything short of DIRECT, because a ledger that scores
     * "adjacent to Kubernetes" at 0.8 reads as coverage the resume cannot
     * actually show.
     */
    public static double credit(ExperienceLevel level) {
        return switch (level) {
            case DIRECT -> 1.0;
            case ADJACENT -> 0.5;
            case CONCEPTUAL -> 0.3;
            case TRANSFERABLE -> 0.15;
            case NONE -> 0.0;
        };
    }

    public static ResumeUse useFor(ExperienceLevel level) {
        return switch (level) {
            case DIRECT -> ResumeUse.CLAIM;
            case NONE -> ResumeUse.OMIT;
            default -> ResumeUse.EMPHASISE_EVIDENCE;
        };
    }

    /** Sorts the entries and computes the totals. */
    public static CoverageLedger of(Long postingId, String title, String requirementSource,
            List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparingInt((Entry e) -> e.requirement().importance().ordinal())
                .thenComparingInt(e -> e.level().ordinal())
                .thenComparing(e -> e.requirement().term().toLowerCase()));

        double weight = 0;
        double covered = 0;
        int requiredDirect = 0;
        int requiredTotal = 0;
        Map<String, Double> priority = new LinkedHashMap<>();
        // A set of alternatives - "C, C++, or Rust" - is one requirement met by its
        // best option, so it is counted once: its strongest importance, its best
        // evidence. Counting each option would score a candidate with Rust as
        // missing two thirds of something the posting says any one of satisfies.
        Map<String, double[]> alternatives = new LinkedHashMap<>();
        for (Entry entry : sorted) {
            double w = entry.requirement().importance().weight();
            double earned = w * credit(entry.level());
            String group = entry.requirement().alternativeOf();
            if (group == null) {
                weight += w;
                covered += earned;
                if (entry.requirement().importance() == RequirementImportance.REQUIRED) {
                    requiredTotal++;
                    if (entry.level() == ExperienceLevel.DIRECT) {
                        requiredDirect++;
                    }
                }
            } else {
                double[] best = alternatives.computeIfAbsent(group,
                        k -> new double[] {0, 0, RequirementImportance.SIGNAL.ordinal(), 0});
                best[0] = Math.max(best[0], w);
                best[1] = Math.max(best[1], credit(entry.level()));
                best[2] = Math.min(best[2], entry.requirement().importance().ordinal());
                if (entry.level() == ExperienceLevel.DIRECT) {
                    best[3] = 1;
                }
            }
            for (EvidenceRef ref : entry.evidence()) {
                priority.merge(ref.sourceId(), earned, Double::sum);
            }
        }
        for (double[] best : alternatives.values()) {
            weight += best[0];
            covered += best[0] * best[1];
            if ((int) best[2] == RequirementImportance.REQUIRED.ordinal()) {
                requiredTotal++;
                if (best[3] == 1) {
                    requiredDirect++;
                }
            }
        }

        Map<String, Double> ranked = new LinkedHashMap<>();
        priority.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> ranked.put(e.getKey(), Math.round(e.getValue() * 1000) / 1000.0));

        double coverage = weight == 0 ? 0 : Math.round(covered / weight * 1000) / 1000.0;
        return new CoverageLedger(postingId, title, requirementSource, List.copyOf(sorted),
                coverage, requiredDirect, requiredTotal, ranked);
    }
}
