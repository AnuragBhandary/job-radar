package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.Entry;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger.EvidenceRef;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.List;
import java.util.Map;

/**
 * A ledger as text for a terminal.
 *
 * <p>✓ is DIRECT, △ is evidence that may be emphasised but not claimed, ✗ is
 * nothing. The three are the same three a resume will eventually be allowed to
 * do with a requirement, so the symbols are the plan in miniature.
 */
public final class LedgerFormatter {

    private LedgerFormatter() {
    }

    public static String format(CoverageLedger ledger, boolean verbose) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%n%s (posting %s) - requirements: %s%n",
                ledger.title(), ledger.postingId(), ledger.requirementSource()));
        out.append(String.format("Weighted coverage %.0f%% · required with direct evidence %d of %d%n",
                ledger.weightedCoverage() * 100, ledger.requiredDirect(), ledger.requiredTotal()));

        for (RequirementImportance importance : RequirementImportance.values()) {
            List<Entry> group = ledger.entries().stream()
                    .filter(e -> e.requirement().importance() == importance)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            out.append('\n').append(title(importance)).append('\n');
            for (Entry entry : group) {
                out.append("  ").append(symbol(entry.level())).append(' ')
                        .append(entry.requirement().term())
                        .append(entry.requirement().alternativeOf() == null ? ""
                                : " (one of \"" + entry.requirement().alternativeOf() + "\")")
                        .append(" — ")
                        .append(entry.level());
                if (!entry.via().isEmpty() && entry.level() != ExperienceLevel.DIRECT) {
                    out.append(" via ").append(String.join(", ", entry.via()));
                }
                if (entry.matchedBy() == CoverageLedger.MatchedBy.TEXT) {
                    out.append(" (wording)");
                }
                if (!entry.evidence().isEmpty()) {
                    out.append(" — ").append(String.join(", ",
                            entry.evidence().stream().map(EvidenceRef::sourceId).toList()));
                }
                out.append('\n');
                if (verbose) {
                    if (!entry.evidence().isEmpty()) {
                        out.append("      \"").append(shorten(entry.evidence().getFirst().text(), 90))
                                .append("\"\n");
                    }
                    out.append("      posting: \"").append(shorten(entry.requirement().quote(), 90))
                            .append("\"\n");
                }
                if (verbose || entry.level() != ExperienceLevel.DIRECT) {
                    out.append("      ").append(entry.note()).append('\n');
                }
            }
        }

        if (!ledger.sourcePriority().isEmpty()) {
            out.append("\nSource items that answer the most of this posting\n");
            ledger.sourcePriority().entrySet().stream().limit(6)
                    .forEach(e -> out.append(String.format("  %-48s %5.2f%n", e.getKey(), e.getValue())));
        }
        return out.toString();
    }

    private static String title(RequirementImportance importance) {
        return switch (importance) {
            case REQUIRED -> "Required";
            case PREFERRED -> "Preferred";
            case SIGNAL -> "Mentioned (not stated as a requirement)";
        };
    }

    private static String symbol(ExperienceLevel level) {
        return switch (level) {
            case DIRECT -> "✓";
            case NONE -> "✗";
            default -> "△";
        };
    }

    static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /** For the priority map's type in a caller that only wants the top few. */
    public static List<Map.Entry<String, Double>> top(CoverageLedger ledger, int n) {
        return ledger.sourcePriority().entrySet().stream().limit(n).toList();
    }
}
