package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources.SourceItem;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one rewrite is allowed to rest on - as narrowly as it can be said.
 *
 * <h2>Primary and secondary evidence</h2>
 * <b>Primary</b> is what the sentence itself carries: the technologies it names and
 * the tags written on it. Only primary evidence becomes a rewrite target.
 *
 * <p><b>Secondary</b> is project context that is true of this particular sentence,
 * each entry with the reason it applies:
 * <ul>
 *   <li>the language and framework of a single-language project - code written in
 *       a Java project is Java code;</li>
 *   <li>one stack item the sentence refers to without naming - "containerised"
 *       means the project's Docker, "persistence" its one relational database,
 *       "events" its one broker, "caching" its Redis.</li>
 * </ul>
 * Nothing else from the stack line reaches a bullet. The first rewrite benchmark
 * gave every bullet its whole project stack, and the model obliged by tacking
 * "using Python" onto sentences about request validation. A bullet about Redis
 * leases does not get Kafka because another bullet of the same project used it.
 *
 * @param sourceText     the words being rewritten. Claims of ownership, scale,
 *                       deployment and every qualifier are judged against this.
 * @param supportText    the source text plus its parent's name and its evidence
 *                       terms. Names and numbers may come from anywhere in this.
 * @param secondaryTerms term to the reason it applies to this sentence
 */
public record EvidenceScope(
        String sourceId,
        String sourceText,
        String supportText,
        Set<String> primaryTerms,
        Map<String, String> secondaryTerms) {

    public EvidenceScope {
        primaryTerms = Set.copyOf(primaryTerms);
        secondaryTerms = Map.copyOf(secondaryTerms);
    }

    /** Everything a rewrite may name: primary and justified secondary. */
    public Set<String> terms() {
        Set<String> all = new LinkedHashSet<>(primaryTerms);
        all.addAll(secondaryTerms.keySet());
        return all;
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Set<String> RELATIONAL =
            Set.of("postgresql", "mysql", "mariadb", "sqlite", "oracle", "cockroachdb");
    private static final Set<String> BROKERS =
            Set.of("kafka", "rabbitmq", "pulsar", "nats", "sqs", "sns", "kinesis");
    private static final Pattern CONTAINERS = Pattern.compile("\\bcontaineri[sz]\\w*|\\bcontainers?\\b", CI);
    private static final Pattern PERSISTENCE = Pattern.compile("\\bpersist\\w*|\\bdatabases?\\b", CI);
    private static final Pattern EVENTS = Pattern.compile("\\bevent-driven\\b|\\bevents?\\b|\\bmessag\\w*|\\bqueues?\\b", CI);
    private static final Pattern CACHING = Pattern.compile("\\bcach(e|es|ed|ing)\\b", CI);

    /** The scope of one bullet, or empty when the id names no bullet. */
    public static Optional<EvidenceScope> forBullet(ResumeSources sources, String sourceId) {
        Optional<SourceItem> found = sources.find(sourceId).filter(SourceItem::isBullet);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SourceItem bullet = found.get();
        Set<String> primary = new LinkedHashSet<>(bullet.terms());
        Map<String, String> secondary = new LinkedHashMap<>();
        if (bullet.kind() == ResumeSources.Kind.PROJECT_BULLET) {
            sources.all().stream()
                    .filter(item -> item.kind() == ResumeSources.Kind.PROJECT_STACK
                            && item.parent().equals(bullet.parent()))
                    .findFirst()
                    .ifPresent(stack -> secondary.putAll(justified(bullet.text(), stack.text(), primary)));
        }
        StringBuilder support = new StringBuilder(bullet.text()).append('\n').append(bullet.parent());
        primary.forEach(term -> support.append('\n').append(term));
        secondary.keySet().forEach(term -> support.append('\n').append(term));
        return Optional.of(new EvidenceScope(sourceId, bullet.text(), support.toString(),
                primary, secondary));
    }

    /** The stack items that are true of this sentence, each with its reason. */
    static Map<String, String> justified(String bulletText, String stackText, Set<String> primary) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> stack = TechVocabulary.found(stackText).stream()
                .map(t -> PostingRequirements.ALIASES.getOrDefault(t, t))
                .distinct()
                .toList();

        List<String> languages = stack.stream()
                .filter(t -> PostingRequirements.categoryOf(t) == RequirementCategory.LANGUAGE)
                .toList();
        if (languages.size() == 1) {
            for (String term : stack) {
                RequirementCategory category = PostingRequirements.categoryOf(term);
                if ((category == RequirementCategory.LANGUAGE
                        || category == RequirementCategory.FRAMEWORK) && !primary.contains(term)) {
                    out.put(term, "the project is written in " + languages.getFirst() + " alone");
                }
            }
        }
        if (stack.contains("docker")) {
            justify(out, primary, "docker", CONTAINERS, bulletText);
        }
        List<String> relational = stack.stream().filter(RELATIONAL::contains).toList();
        if (relational.size() == 1) {
            justify(out, primary, relational.getFirst(), PERSISTENCE, bulletText);
        }
        List<String> brokers = stack.stream().filter(BROKERS::contains).toList();
        if (brokers.size() == 1) {
            justify(out, primary, brokers.getFirst(), EVENTS, bulletText);
        }
        if (stack.contains("redis")) {
            justify(out, primary, "redis", CACHING, bulletText);
        }
        return out;
    }

    private static void justify(Map<String, String> out, Set<String> primary, String term,
            Pattern wording, String text) {
        if (primary.contains(term) || out.containsKey(term)) {
            return;
        }
        Matcher matcher = wording.matcher(text);
        if (matcher.find()) {
            out.put(term, "the sentence says '" + matcher.group() + "'");
        }
    }
}
