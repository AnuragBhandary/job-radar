package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One posting's resume, and every decision that produced it.
 *
 * <p>The resume is what gets rendered. Everything else is so a person can check it:
 * what the posting asked for, which evidence answered each requirement, which
 * wording was chosen and why, and what was left out.
 *
 * @param ledger     the coverage ledger it was planned from; null when none was built
 * @param selections every evidence item considered, in the order the resume prints
 *                   them, with whether it was included
 * @param coverage   one row per ledger requirement: the evidence chosen for it, or
 *                   why there is none
 * @param reasons    why a LEGACY plan is legacy
 */
public record TailoringPlan(
        Mode mode,
        TailoredResume resume,
        CoverageLedger ledger,
        List<Selection> selections,
        List<Coverage> coverage,
        List<String> reasons) {

    public enum Mode {
        /** Bullets selected from the evidence bank, skills ordered by the ledger. */
        EVIDENCE,
        /** Tailored exactly as before the bank existed. */
        LEGACY
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
     * @param evidenceIds the included items that answer it; empty when none does
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
        reasons = List.copyOf(reasons);
    }

    public static TailoringPlan legacy(TailoredResume resume, CoverageLedger ledger,
            List<String> reasons) {
        return new TailoringPlan(Mode.LEGACY, resume, ledger, List.of(), List.of(), reasons);
    }

    public boolean fromEvidence() {
        return mode == Mode.EVIDENCE;
    }

    /** Required requirements no included evidence answers. */
    public List<String> requiredGaps() {
        return coverage.stream()
                .filter(c -> c.importance() == RequirementImportance.REQUIRED && c.evidenceIds().isEmpty())
                .map(Coverage::requirement)
                .toList();
    }

    /** One line for the application row and the log. */
    public String note() {
        StringBuilder sb = new StringBuilder(resume.note());
        if (mode == Mode.EVIDENCE) {
            long included = selections.stream().filter(Selection::included).count();
            long variants = selections.stream()
                    .filter(s -> s.included() && !EvidenceItem.CLAIM.equals(s.variantId())).count();
            sb.append("; evidence plan: ").append(included).append(" item(s)");
            if (variants > 0) {
                sb.append(", ").append(variants).append(" approved variant(s)");
            }
            List<String> gaps = requiredGaps();
            if (!gaps.isEmpty()) {
                sb.append("; no evidence for ").append(String.join(", ", gaps));
            }
        } else {
            sb.append("; tailored without the evidence bank: ")
                    .append(reasons.isEmpty() ? "no reason recorded" : reasons.getFirst());
        }
        return sb.length() <= MAX_NOTE ? sb.toString() : sb.substring(0, MAX_NOTE - 3) + "...";
    }

    /** The whole plan, for the terminal. */
    public String explain() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resume plan: ").append(mode == Mode.EVIDENCE
                ? "selected from the evidence bank" : "tailored as before (no evidence bank used)")
                .append('\n');
        reasons.forEach(r -> sb.append("  ").append(r).append('\n'));
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
        sb.append("\nSkills, in printed order: ").append(resume.skills().stream()
                .map(ResumeModel.SkillGroup::group).collect(Collectors.joining(", "))).append('\n');
        if (!resume.droppedProjects().isEmpty()) {
            sb.append("Projects left off: ").append(String.join(", ", resume.droppedProjects()))
                    .append('\n');
        }
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
