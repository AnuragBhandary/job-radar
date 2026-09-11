package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources.SourceItem;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What one rewrite is allowed to rest on.
 *
 * <p>Deliberately local. A bullet's scope is that bullet, its tags and - for a
 * project bullet - the project's stack line. Not the whole resume: he has used
 * Redis, but a bullet about document upload that suddenly mentions Redis has
 * moved a fact from one accomplishment to another, and that is the drift a
 * rewrite must not be allowed.
 *
 * @param sourceText  the words being rewritten. Claims of ownership, scale or
 *                    deployment must be found here and only here.
 * @param supportText the source text plus its tags, stack and parent name. Names
 *                    and numbers may come from anywhere in this.
 * @param terms       normalised technologies the scope carries
 */
public record EvidenceScope(String sourceId, String sourceText, String supportText, Set<String> terms) {

    /** The scope of one bullet, or empty when the id names no bullet. */
    public static Optional<EvidenceScope> forBullet(ResumeSources sources, String sourceId) {
        Optional<SourceItem> found = sources.find(sourceId).filter(SourceItem::isBullet);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SourceItem bullet = found.get();
        Set<String> terms = new LinkedHashSet<>(bullet.terms());
        StringBuilder support = new StringBuilder(bullet.text())
                .append('\n').append(bullet.parent());
        bullet.terms().forEach(term -> support.append('\n').append(term));
        if (bullet.kind() == ResumeSources.Kind.PROJECT_BULLET) {
            sources.all().stream()
                    .filter(item -> item.kind() == ResumeSources.Kind.PROJECT_STACK
                            && item.parent().equals(bullet.parent()))
                    .findFirst()
                    .ifPresent(stack -> {
                        terms.addAll(stack.terms());
                        support.append('\n').append(stack.text());
                        stack.terms().forEach(term -> support.append('\n').append(term));
                    });
        }
        return Optional.of(new EvidenceScope(sourceId, bullet.text(), support.toString(),
                Set.copyOf(terms)));
    }

    /**
     * The scope of a summary: the whole resume may be named, but claims of scale,
     * ownership or years must already be in the summary being rewritten.
     */
    public static EvidenceScope forSummary(ResumeSources sources, String summaryId,
            String summaryText) {
        Set<String> terms = new LinkedHashSet<>();
        TechVocabulary.found(summaryText).forEach(t -> terms.add(t.toLowerCase(Locale.ROOT)));
        StringBuilder support = new StringBuilder(summaryText);
        for (SourceItem item : sources.all()) {
            terms.addAll(item.terms());
            support.append('\n').append(item.text()).append('\n').append(item.parent());
            item.terms().forEach(term -> support.append('\n').append(term));
        }
        return new EvidenceScope(summaryIdOf(summaryId), summaryText, support.toString(),
                Set.copyOf(terms));
    }

    public static String summaryIdOf(String summaryId) {
        return "summary-" + summaryId;
    }
}
