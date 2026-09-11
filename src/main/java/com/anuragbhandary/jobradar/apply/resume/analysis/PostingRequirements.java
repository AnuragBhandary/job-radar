package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.knowledge.experience.SkillGraph;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a posting asks for, read without a model.
 *
 * <p>The deterministic floor under the coverage ledger. It finds the technologies
 * {@link TechVocabulary} knows and a short, hand-written list of non-technology
 * requirements - REST APIs, testing, on-call, cross-functional work - and decides
 * how strongly each is asked for from the section it appears in.
 *
 * <h2>How importance is read</h2>
 * The description is cut into units - lines, then sentences and inline list
 * items - and walked in order. A heading ("Requirements", "Nice to have",
 * "Benefits") sets the mode for what follows; a unit can override it on its own
 * ("Kafka is a plus"). Anything the title names is REQUIRED, because the title is
 * the job.
 *
 * <p>It is deliberately literal. A posting with no headings at all - many are -
 * yields SIGNAL for everything, which is true to the text and is exactly the
 * case where a model's reading may do better. That comparison is what the
 * benchmark measures.
 *
 * <p>Every quote is a substring of the text it came from, so each requirement
 * here passes {@link QuoteGrounding} by construction.
 */
public final class PostingRequirements {

    private PostingRequirements() {
    }

    /**
     * Vocabulary entries that are also ordinary English or a company name.
     *
     * <p>Counted only when the posting capitalises them - "Go", "Spring",
     * "Oracle" - because "go beyond", "this spring" and "the rest of the team"
     * are in half the postings in the corpus.
     */
    public static final Set<String> AMBIGUOUS = Set.of(
            "go", "spring", "express", "rails", "gin", "beam", "swift", "oracle",
            "lambda", "vault", "consul", "nats", "pulsar", "agile", "scrum", "kanban",
            "gemini", "anthropic", "openai", "groq", "sns", "ecs", "dbt");

    /** Vocabulary entries never read on their own; a phrase covers them. */
    private static final Set<String> SKIPPED = Set.of("rest");

    /**
     * Two spellings of one thing.
     *
     * <p>Without this "Postgres" in a posting is positioned as ADJACENT to his
     * "PostgreSQL" - the same database, reported as a gap.
     */
    public static final Map<String, String> ALIASES = Map.of(
            "postgres", "postgresql",
            "k8s", "kubernetes",
            "websocket", "websockets",
            "golang", "go");

    /**
     * A non-technology requirement and the wording that asks for it.
     *
     * <p>The same pattern is used on the posting and, later, on his bullets: a
     * bullet that matches the phrase a posting used is evidence the resume already
     * says it.
     */
    public record Phrase(String term, RequirementCategory category, Pattern pattern) {

        public boolean foundIn(String text) {
            return text != null && pattern.matcher(text).find();
        }
    }

    private static Phrase phrase(String term, RequirementCategory category, String regex) {
        return new Phrase(term, category, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /**
     * Order matters: the first match names a requirement, so the more specific
     * phrase is listed before the broader one it overlaps.
     *
     * <p>The canonical terms are chosen to be terms the rest of the system
     * already knows - "api design", "asynchronous processing", "backend
     * engineering", "relational databases", "testing" are {@link SkillGraph}
     * concepts and foundations - so positioning a phrase finds real evidence
     * rather than nothing.
     */
    public static final List<Phrase> PHRASES = List.of(
            phrase("REST APIs", RequirementCategory.ARCHITECTURE,
                    "\\brest(ful)?[- ]?(apis?|services|endpoints|interfaces)\\b"),
            phrase("API design", RequirementCategory.ARCHITECTURE,
                    "\\bapi design\\b|\\bdesign(ing)?( and build(ing)?)?( \\w+)? apis\\b"),
            phrase("system design", RequirementCategory.ARCHITECTURE,
                    "\\bsystems? design\\b|\\bsystem architecture\\b"),
            phrase("backend engineering", RequirementCategory.RESPONSIBILITY,
                    "\\bback[- ]?end\\b|\\bserver[- ]side\\b"),
            phrase("scalability", RequirementCategory.ARCHITECTURE,
                    "\\bscal(able|ability)\\b|\\bat scale\\b"),
            phrase("real-time systems", RequirementCategory.ARCHITECTURE,
                    "\\breal[- ]time\\b"),
            phrase("asynchronous processing", RequirementCategory.ARCHITECTURE,
                    "\\basynchronous(ly)?\\b|\\basync\\b"),
            phrase("relational databases", RequirementCategory.DATABASE,
                    "\\b(relational|sql) databases?\\b"),
            phrase("data modelling", RequirementCategory.ARCHITECTURE,
                    "\\bdata model(l)?ing\\b"),
            phrase("testing", RequirementCategory.RESPONSIBILITY,
                    "\\b(unit|integration|automated|end[- ]to[- ]end) test(s|ing)?\\b"
                            + "|\\btest[- ]driven\\b"),
            phrase("code review", RequirementCategory.RESPONSIBILITY,
                    "\\bcode reviews?\\b"),
            phrase("on-call", RequirementCategory.RESPONSIBILITY,
                    "\\bon[- ]call\\b"),
            phrase("service ownership", RequirementCategory.RESPONSIBILITY,
                    "\\bown(ing)? (your |the |our )?(services|systems|features|products?)\\b"
                            + "|\\bend[- ]to[- ]end ownership\\b"),
            phrase("cross-functional collaboration", RequirementCategory.SOFT_SKILL,
                    "\\bcross[- ]functional(ly)?\\b"
                            + "|\\bcollaborat\\w* (closely |directly )?with (product|design"
                            + "|data|engineers|stakeholders)"),
            phrase("communication", RequirementCategory.SOFT_SKILL,
                    "\\b(written and verbal|verbal and written) communication\\b"
                            + "|\\bcommunication skills\\b"));

    private static final Set<String> PHRASE_TERMS = PHRASES.stream()
            .map(p -> key(p.term()))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * A version attached to a name: "Python (3.14)", "Java 17+", "Python 3.x".
     *
     * <p>Only a separate token, so "S3" and "EC2" are untouched. A version does not
     * change whose evidence it is: "Python (3.14)" was positioned as ADJACENT to his
     * Python in the first rewrite benchmark, which is the same language reported as
     * a gap.
     */
    private static final Pattern VERSION = Pattern.compile(
            "\\s*\\(\\s*v?\\d+(?:\\.(?:\\d+|x))*\\+?\\s*\\)|\\s+v?\\d+(?:\\.(?:\\d+|x))*\\+?$",
            Pattern.CASE_INSENSITIVE);

    /** Words a model wraps around a technology name: "strong Java experience". */
    private static final Set<String> FILLER = Set.of(
            "apache", "experience", "with", "in", "using", "knowledge", "of",
            "proficiency", "proficient", "strong", "solid", "hands-on", "the",
            "familiarity", "familiar", "expertise", "skills", "skill", "development",
            "programming", "language", "framework");

    // ------------------------------------------------------------------
    // Section reading
    // ------------------------------------------------------------------

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final Pattern PREFERRED_HEADING = Pattern.compile(
            "^\\W*(preferred|nice[- ]to[- ]haves?|(added |extra )?bonus( points)?"
                    + "|desirable|good to have|pluses|what would make you stand out"
                    + "|it would be great if|ideally)\\b", CI);

    private static final Pattern REQUIRED_HEADING = Pattern.compile(
            "^\\W*(basic qualifications|minimum qualifications|requirements?|required"
                    + "|must[- ]haves?|qualifications|what you('| wi)ll need"
                    + "|what we('| a)re looking for|who you are|about you|you have"
                    + "|you('| wi)ll have|you bring|what you('| wi)ll bring"
                    + "|skills (and|&) experience|your (profile|background|experience|skills))\\b",
            CI);

    private static final Pattern NEUTRAL_HEADING = Pattern.compile(
            "^\\W*(about (us|the (team|role|company|job))|what you('| wi)ll do"
                    + "|responsibilities|your (impact|role|toolkit|day)|the role|role overview"
                    + "|the engineering challenge|benefits|what we offer|perks"
                    + "|our (story|stack|culture|engineering culture|mission)|tech stack"
                    + "|diversity|equal opportunit|how we work|why join)", CI);

    private static final Pattern INLINE_PREFERRED = Pattern.compile(
            "\\b(a (big |huge |strong |real )?plus|nice[- ]to[- ]have|bonus|preferred"
                    + "|preferably|ideally|desirable|advantageous|not required"
                    + "|not a requirement)\\b", CI);

    private static final Pattern INLINE_REQUIRED = Pattern.compile(
            "\\b(required|must have|must be|you must|mandatory|essential|minimum of"
                    + "|at least \\d)", CI);

    /** Sentence ends and inline list separators - " - ", " • ". */
    private static final String UNIT_BREAK =
            "(?<=[.!?;])\\s+(?=[A-Z(\"'“‘])|\\s+[-•·▪*]\\s+";

    private static final Pattern LEADING_BULLET =
            Pattern.compile("^[\\s\\-•·▪*]+");

    /** A quote longer than this is cut to a window around the term. */
    static final int MAX_QUOTE = 280;

    /** The text a requirement's quote must be found in: the title and the description. */
    public static String groundingText(String title, String description) {
        return (title == null ? "" : title) + "\n" + (description == null ? "" : description);
    }

    /**
     * Every requirement this posting states, merged on id, strongest importance
     * kept.
     */
    public static List<Requirement> extract(String title, String description) {
        Map<String, Requirement> found = new LinkedHashMap<>();

        String heading = title == null ? "" : title.strip();
        if (!heading.isEmpty()) {
            collect(heading, RequirementImportance.REQUIRED, found);
        }

        RequirementImportance mode = RequirementImportance.SIGNAL;
        if (description != null) {
            for (String line : description.split("\\R")) {
                for (String raw : line.split(UNIT_BREAK)) {
                    String unit = LEADING_BULLET.matcher(raw).replaceFirst("").strip();
                    if (unit.isEmpty()) {
                        continue;
                    }
                    String content = unit;
                    Optional<RequirementImportance> section = headingMode(unit);
                    if (section.isPresent()) {
                        mode = section.get();
                        int colon = unit.indexOf(':');
                        if (colon >= 0 && colon <= 60) {
                            content = unit.substring(colon + 1).strip();
                        }
                    }
                    if (!content.isEmpty()) {
                        collect(content, importanceOf(content, mode), found);
                    }
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** The mode a heading sets, when the unit is one. */
    static Optional<RequirementImportance> headingMode(String unit) {
        String probe = unit.replace('’', '\'');
        int colon = unit.indexOf(':');
        Object[][] headings = {
                {PREFERRED_HEADING, RequirementImportance.PREFERRED},
                {REQUIRED_HEADING, RequirementImportance.REQUIRED},
                {NEUTRAL_HEADING, RequirementImportance.SIGNAL}};
        for (Object[] heading : headings) {
            Matcher matcher = ((Pattern) heading[0]).matcher(probe);
            if (matcher.find()) {
                boolean shortLine = unit.length() <= 60;
                boolean colonSoon = colon >= 0 && colon <= matcher.end() + 30;
                if (shortLine || colonSoon) {
                    return Optional.of((RequirementImportance) heading[1]);
                }
            }
        }
        return Optional.empty();
    }

    static RequirementImportance importanceOf(String content, RequirementImportance mode) {
        String probe = content.replace('’', '\'');
        if (INLINE_PREFERRED.matcher(probe).find()) {
            return RequirementImportance.PREFERRED;
        }
        if (mode == RequirementImportance.SIGNAL && INLINE_REQUIRED.matcher(probe).find()) {
            return RequirementImportance.REQUIRED;
        }
        return mode;
    }

    private static void collect(String content, RequirementImportance importance,
            Map<String, Requirement> found) {
        Set<String> terms = TechVocabulary.found(content);
        for (String term : terms) {
            if (SKIPPED.contains(term)) {
                continue;
            }
            if (AMBIGUOUS.contains(term) && !capitalised(content, term)) {
                continue;
            }
            // "spring" inside "spring boot" is the same mention, not a second one.
            if (terms.stream().anyMatch(other -> !other.equals(term)
                    && other.startsWith(term + " "))) {
                continue;
            }
            String canonical = ALIASES.getOrDefault(term, term);
            add(found, Requirement.of(canonical, categoryOf(canonical), importance,
                    quoteAround(content, indexOfWord(content, term))));
        }
        for (Phrase phrase : PHRASES) {
            Matcher matcher = phrase.pattern().matcher(content);
            if (matcher.find()) {
                add(found, Requirement.of(phrase.term(), phrase.category(), importance,
                        quoteAround(content, matcher.start())));
            }
        }
    }

    private static void add(Map<String, Requirement> found, Requirement requirement) {
        Requirement existing = found.get(requirement.id());
        if (existing == null
                || requirement.importance().ordinal() < existing.importance().ordinal()) {
            found.put(requirement.id(), requirement);
        }
    }

    // ------------------------------------------------------------------
    // Terms
    // ------------------------------------------------------------------

    /**
     * The name to position a requirement under.
     *
     * <p>Model terms arrive as "Strong Java experience" or "Apache Kafka" or
     * "design REST APIs"; the positioner and the index are keyed on "java",
     * "kafka" and "REST APIs". Filler is removed only when what remains is
     * exactly a known technology - "Kafka Streams" is not reduced to "kafka",
     * because it is a narrower claim than the one his evidence supports.
     */
    public static String canonicalSubject(String term) {
        if (term == null) {
            return "";
        }
        String stripped = VERSION.matcher(term.strip()).replaceAll("").strip();
        if (stripped.isEmpty()) {
            stripped = term.strip();
        }
        String k = key(stripped);
        if (ALIASES.containsKey(k)) {
            return ALIASES.get(k);
        }
        String reduced = Arrays.stream(k.split("\\s+"))
                .filter(word -> !FILLER.contains(word))
                .collect(Collectors.joining(" "));
        if (ALIASES.containsKey(reduced)) {
            return ALIASES.get(reduced);
        }
        if (!reduced.isEmpty() && TechVocabulary.found(reduced).contains(reduced)) {
            return reduced;
        }
        Optional<Phrase> phrase = phraseFor(stripped);
        return phrase.map(Phrase::term).orElse(stripped);
    }

    /** The phrase that names this text, if one does. */
    public static Optional<Phrase> phraseFor(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String k = key(text);
        for (Phrase phrase : PHRASES) {
            if (key(phrase.term()).equals(k) || phrase.foundIn(text)) {
                return Optional.of(phrase);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this subject is a technology, for which only the index may supply
     * evidence.
     *
     * <p>A technology never matches on bullet wording: a bullet that happens to
     * contain the word "containers" is not evidence of Kubernetes, and the
     * positioner's ADJACENT verdict is the honest one.
     */
    public static boolean isTechnology(String subject) {
        if (subject == null || subject.isBlank() || PHRASE_TERMS.contains(key(subject))) {
            return false;
        }
        return !TechVocabulary.found(subject).isEmpty() || SkillGraph.knows(subject);
    }

    public static boolean isPhrase(String subject) {
        return subject != null && PHRASE_TERMS.contains(key(subject));
    }

    private static final Set<String> LANGUAGES = Set.of("java", "kotlin", "scala",
            "python", "golang", "go", "rust", "c++", "c#", "typescript", "javascript", "ruby",
            "php", "swift", "elixir", "clojure");
    private static final Set<String> DATABASES = Set.of("postgresql", "postgres", "mysql",
            "mariadb", "sqlite", "oracle", "mongodb", "cassandra", "dynamodb", "redis",
            "memcached", "elasticsearch", "opensearch", "clickhouse", "snowflake", "bigquery",
            "redshift", "neo4j", "cockroachdb", "vitess", "duckdb");
    private static final Set<String> CLOUD = Set.of("docker", "kubernetes", "k8s", "helm",
            "terraform", "pulumi", "ansible", "aws", "gcp", "azure", "lambda", "ecs", "eks",
            "fargate", "cloudformation", "linux", "nginx", "envoy", "istio", "consul", "vault");
    private static final Set<String> FRAMEWORKS = Set.of("spring boot", "spring cloud",
            "spring security", "spring data", "spring", "quarkus", "micronaut", "vert.x",
            "dropwizard", "hibernate", "jpa", "fastapi", "django", "flask", "celery",
            "pydantic", "asyncio", "node.js", "express", "nestjs", "react", "next.js", "vue",
            "angular", "rails", "laravel", ".net", "gin", "actix", "junit", "pytest",
            "mockito", "testcontainers", "cypress", "pytorch", "tensorflow", "scikit-learn",
            "keras", "huggingface", "langchain", "llamaindex");
    private static final Set<String> ARCHITECTURE = Set.of("microservices", "monolith",
            "event-driven", "event sourcing", "cqrs", "domain-driven", "ddd",
            "distributed systems", "observability", "rest", "graphql", "grpc", "protobuf",
            "websockets", "websocket", "openapi", "swagger", "soap", "webhooks");

    public static RequirementCategory categoryOf(String vocabularyTerm) {
        String term = key(vocabularyTerm);
        if (LANGUAGES.contains(term)) {
            return RequirementCategory.LANGUAGE;
        }
        if (DATABASES.contains(term)) {
            return RequirementCategory.DATABASE;
        }
        if (CLOUD.contains(term)) {
            return RequirementCategory.CLOUD;
        }
        if (FRAMEWORKS.contains(term)) {
            return RequirementCategory.FRAMEWORK;
        }
        if (ARCHITECTURE.contains(term)) {
            return RequirementCategory.ARCHITECTURE;
        }
        return RequirementCategory.TECHNOLOGY;
    }

    private static Pattern wordPattern(String term) {
        return Pattern.compile("(?<![\\w+#.])" + Pattern.quote(term) + "(?![\\w+#])", CI);
    }

    private static boolean capitalised(String content, String term) {
        Matcher matcher = wordPattern(term).matcher(content);
        while (matcher.find()) {
            if (Character.isUpperCase(content.charAt(matcher.start()))) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfWord(String content, String term) {
        Matcher matcher = wordPattern(term).matcher(content);
        return matcher.find() ? matcher.start() : 0;
    }

    /** The unit, or a window of it around the term. Always a substring. */
    static String quoteAround(String content, int index) {
        if (content.length() <= MAX_QUOTE) {
            return content;
        }
        int start = Math.max(0, Math.min(index, content.length()) - 120);
        int end = Math.min(content.length(), start + MAX_QUOTE);
        return content.substring(start, end).strip();
    }

    static String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
