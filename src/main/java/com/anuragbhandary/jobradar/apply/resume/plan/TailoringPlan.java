package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One posting's resume, the evidence behind it, and every decision that produced it.
 *
 * <p>The resume is what gets rendered. {@link #evidence} is what the cover letter and
 * career answers of the same application are grounded on - the same ledger, the same
 * selection. Everything else is so a person can check it.
 *
 * @param ledger     the coverage ledger it was planned from
 * @param selections every evidence item considered, in the order the resume prints
 *                   them, with whether it was included
 * @param coverage   one row per ledger requirement: the printed evidence for it, or
 *                   why there is none
 * @param evidence   the application's evidence context, built from this plan
 */
public record TailoringPlan(
        Mode mode,
        TailoredResume resume,
        CoverageLedger ledger,
        List<Selection> selections,
        List<Coverage> coverage,
        ApplicationEvidenceContext evidence) {

    public enum Mode {
        /** Bullets and wordings selected from the evidence bank against the ledger. */
        EVIDENCE,
        /**
         * {@code job-radar.evidence.tailoring=false}: the older tag-based selection,
         * over the same canonical evidence-bank claims. A rollback of the selection
         * strategy, never of where the claims come from.
         */
        SIMPLE
    }

    /**
     * @param section    the company or project it is printed under
     * @param variantId  {@link EvidenceItem#CLAIM}, or the approved variant chosen
     * @param relevance  importance-weighted match score against this posting
     * @param because    each requirement it answered, and how
     */
    public record Selection(String section, String evidenceId, String variantId, String text,
            double relevance, List<String> because, boolean included) {
    }

    /**
     * @param evidenceIds the printed items that answer it; empty when none does
     */
    public record Coverage(String requirement, RequirementImportance importance,
            ExperienceLevel level, CoverageLedger.ResumeUse use, List<String> evidenceIds,
            String note) {
    }

    /** The column this is stored in holds 1,024 characters. */
    static final int MAX_NOTE = 1000;

    public TailoringPlan {
        selections = List.copyOf(selections);
        coverage = List.copyOf(coverage);
    }

    /** The evidence ids printed on the resume, in printed order. */
    public List<String> resumeEvidenceIds() {
        return evidence.resumeEvidenceIds();
    }

    public boolean fromEvidence() {
        return mode == Mode.EVIDENCE;
    }

    /** Required requirements no printed evidence answers. */
    public List<String> requiredGaps() {
        return coverage.stream()
                .filter(c -> c.importance() == RequirementImportance.REQUIRED && c.evidenceIds().isEmpty())
                .map(Coverage::requirement)
                .toList();
    }

    /** One line for the application row and the log. */
    public String note() {
        StringBuilder sb = new StringBuilder(resume.note());
        long included = selections.stream().filter(Selection::included).count();
        long variants = selections.stream()
                .filter(s -> s.included() && !EvidenceItem.CLAIM.equals(s.variantId())).count();
        sb.append(mode == Mode.EVIDENCE ? "; evidence plan: " : "; simple selection: ")
                .append(included).append(" item(s)");
        if (variants > 0) {
            sb.append(", ").append(variants).append(" approved variant(s)");
        }
        List<String> gaps = requiredGaps();
        if (!gaps.isEmpty()) {
            sb.append("; no evidence for ").append(String.join(", ", gaps));
        }
        return sb.length() <= MAX_NOTE ? sb.toString() : sb.substring(0, MAX_NOTE - 3) + "...";
    }

    /** The whole plan, for the terminal. */
    public String explain() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resume plan: ").append(mode == Mode.EVIDENCE
                ? "selected from the evidence bank"
                : "tag selection over the evidence bank (job-radar.evidence.tailoring=false)")
                .append('\n');
        sb.append("Summary: '").append(resume.summary().id()).append("'\n");

        if (!coverage.isEmpty()) {
            sb.append("\nWhat the posting asks for, and the evidence for it:\n");
            for (Coverage row : coverage) {
                sb.append(String.format(Locale.ROOT, "  %-9s %-12s %-30s %s%n", row.importance(),
                        row.level(), shorten(row.requirement(), 30),
                        row.evidenceIds().isEmpty() ? row.note()
                                : ids(row.evidenceIds()) + " - " + row.note()));
            }
        }
        if (!selections.isEmpty()) {
            sb.append("\nEvidence considered (+ printed, - left out):\n");
            String section = null;
            for (Selection s : selections) {
                if (!s.section().equals(section)) {
                    section = s.section();
                    sb.append("  ").append(section).append('\n');
                }
                sb.append(String.format(Locale.ROOT, "    %s %-30s %-14s %6.2f  %s%n",
                        s.included() ? "+" : "-", s.evidenceId(), s.variantId(), s.relevance(),
                        s.because().isEmpty() ? "nothing this posting asks for"
                                : String.join("; ", s.because())));
            }
        }
        sb.append("\nSkills, in printed order: ").append(resume.skills() == null ? ""
                : resume.skills().stream().map(ResumeModel.SkillGroup::group)
                        .collect(Collectors.joining(", "))).append('\n');
        if (!resume.droppedProjects().isEmpty()) {
            sb.append("Projects left off: ").append(String.join(", ", resume.droppedProjects()))
                    .append('\n');
        }
        sb.append("Evidence printed: ").append(String.join(", ", resumeEvidenceIds())).append('\n');
        return sb.toString();
    }

    /** The strongest few, and a count of the rest: a row naming ten items is a search, not an answer. */
    private static String ids(List<String> ids) {
        return ids.size() <= 4 ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, 4)) + " +" + (ids.size() - 4) + " more";
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
