package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a coverage ledger into the brief for one bullet rewrite.
 *
 * <p>A bullet is only pointed at requirements its own words carry - its primary
 * evidence. Project context it is allowed to name is not pushed at it: pushing it
 * is what produced "…for reliable backend workflows using python." last time.
 *
 * <h2>Bullets only</h2>
 * There is deliberately no summary planning here. The first rewrite benchmark
 * produced "one year of professional experience working in Java and Spring Boot":
 * every fact individually true, the combination false, because the professional
 * year was Python and the Java was personal projects. Nothing in this package can
 * check attribution across sentences, so summaries stay the ones he approved,
 * chosen by the deterministic tailor.
 */
public final class RewritePlanner {

    private RewritePlanner() {
    }

    static final int MAX_TARGETS = 5;
    static final int MAX_ADJACENT = 3;
    static final int MAX_PROHIBITED = 12;

    public static RewriteRequest forBullet(EvidenceScope scope, CoverageLedger ledger) {
        List<RewriteRequest.Target> targets = new ArrayList<>();
        List<RewriteRequest.Adjacent> adjacent = new ArrayList<>();
        Set<String> prohibited = new LinkedHashSet<>();

        // Anything DIRECT anywhere in the ledger is his and must never be listed
        // as something not to claim, whatever other row it appears in.
        Set<String> direct = new HashSet<>();
        for (CoverageLedger.Entry entry : ledger.entries()) {
            if (entry.level() == ExperienceLevel.DIRECT) {
                direct.add(key(PostingRequirements.canonicalSubject(entry.requirement().term())));
            }
        }

        // Entries are already ordered required first, strongest level first.
        for (CoverageLedger.Entry entry : ledger.entries()) {
            Requirement requirement = entry.requirement();
            String subject = PostingRequirements.canonicalSubject(requirement.term());
            String subjectKey = key(subject);
            boolean citesThis = entry.evidence().stream()
                    .anyMatch(ref -> ref.sourceId().equals(scope.sourceId()));

            if (entry.level() == ExperienceLevel.DIRECT) {
                boolean supported = citesThis
                        || scope.primaryTerms().contains(subjectKey)
                        || PostingRequirements.phraseFor(subject)
                                .map(p -> p.foundIn(scope.sourceText())).orElse(false);
                if (supported && targets.size() < MAX_TARGETS) {
                    targets.add(new RewriteRequest.Target(requirement.term(),
                            requirement.importance(), requirement.quote()));
                }
                continue;
            }

            if (entry.level() != ExperienceLevel.NONE && adjacent.size() < MAX_ADJACENT) {
                List<String> via = entry.via().stream()
                        .filter(v -> scope.primaryTerms().contains(key(v)))
                        .toList();
                if (!via.isEmpty() || citesThis) {
                    adjacent.add(new RewriteRequest.Adjacent(requirement.term(), entry.level(),
                            via.isEmpty() ? entry.via() : via));
                }
            }
            if ((PostingRequirements.isTechnology(subject) || PostingRequirements.isPhrase(subject))
                    && !direct.contains(subjectKey)
                    && !scope.terms().contains(subjectKey)
                    && prohibited.size() < MAX_PROHIBITED) {
                prohibited.add(requirement.term());
            }
        }

        String original = scope.sourceText();
        int limit = (int) Math.round(original.length() * 1.15) + 10;
        return new RewriteRequest(scope.sourceId(), original, scope, List.copyOf(targets),
                List.copyOf(adjacent), List.copyOf(prohibited), limit);
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
