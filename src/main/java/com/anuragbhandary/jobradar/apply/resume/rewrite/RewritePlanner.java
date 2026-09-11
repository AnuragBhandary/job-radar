package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a coverage ledger into the brief for one rewrite.
 *
 * <p>A bullet is only pointed at requirements its own evidence carries. A posting
 * asking for PostgreSQL does not make the WebSockets bullet a PostgreSQL bullet,
 * however much a model would like to make it one.
 */
public final class RewritePlanner {

    private RewritePlanner() {
    }

    static final int MAX_TARGETS = 5;
    static final int MAX_SUMMARY_TARGETS = 8;
    static final int MAX_ADJACENT = 3;
    static final int MAX_PROHIBITED = 12;

    public static RewriteRequest forBullet(EvidenceScope scope, CoverageLedger ledger) {
        return build(scope, ledger, false);
    }

    public static RewriteRequest forSummary(EvidenceScope scope, CoverageLedger ledger) {
        return build(scope, ledger, true);
    }

    private static RewriteRequest build(EvidenceScope scope, CoverageLedger ledger,
            boolean summary) {
        List<RewriteRequest.Target> targets = new ArrayList<>();
        List<RewriteRequest.Adjacent> adjacent = new ArrayList<>();
        Set<String> prohibited = new LinkedHashSet<>();
        int maxTargets = summary ? MAX_SUMMARY_TARGETS : MAX_TARGETS;

        // Ledger entries are already ordered required first, strongest level first.
        for (CoverageLedger.Entry entry : ledger.entries()) {
            Requirement requirement = entry.requirement();
            String subject = PostingRequirements.canonicalSubject(requirement.term());
            boolean citesThis = entry.evidence().stream()
                    .anyMatch(ref -> ref.sourceId().equals(scope.sourceId()));

            if (entry.level() == ExperienceLevel.DIRECT) {
                boolean supported = summary || citesThis
                        || scope.terms().contains(key(subject))
                        || PostingRequirements.phraseFor(subject)
                                .map(p -> p.foundIn(scope.sourceText())).orElse(false);
                if (supported && targets.size() < maxTargets) {
                    targets.add(new RewriteRequest.Target(requirement.term(),
                            requirement.importance(), requirement.quote()));
                }
                continue;
            }

            if (entry.level() != ExperienceLevel.NONE && adjacent.size() < MAX_ADJACENT) {
                List<String> via = entry.via().stream()
                        .filter(v -> summary || scope.terms().contains(key(v)))
                        .toList();
                if (!via.isEmpty() || citesThis) {
                    adjacent.add(new RewriteRequest.Adjacent(requirement.term(), entry.level(),
                            via.isEmpty() ? entry.via() : via));
                }
            }
            if ((PostingRequirements.isTechnology(subject) || PostingRequirements.isPhrase(subject))
                    && prohibited.size() < MAX_PROHIBITED) {
                prohibited.add(requirement.term());
            }
        }

        String original = scope.sourceText();
        int limit = (int) Math.round(original.length() * 1.15) + 10;
        return new RewriteRequest(scope.sourceId(), original, scope, List.copyOf(targets),
                List.copyOf(adjacent), List.copyOf(prohibited), limit, summary);
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
