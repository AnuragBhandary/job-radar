package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a rewritten resume line may stand in for its source.
 *
 * <p>A sibling of {@link com.anuragbhandary.jobradar.knowledge.experience.ClaimValidator},
 * not a loosening of it. That one checks answers to "have you used X?", which must
 * say so when he has not; a resume line never disclaims anything, so it needs its
 * own rules. The principle is the one the whole rewrite rests on:
 * <strong>new wording is acceptable; new facts are not.</strong>
 *
 * <h2>What is checked</h2>
 * <ol>
 *   <li><b>The source exists</b> and is the one that was asked about.</li>
 *   <li><b>Technologies</b> - every one named is in this item's own evidence, or
 *       is a documented re-framing of it (Kafka messaging as "event-driven").</li>
 *   <li><b>Related-but-not-his terms</b> from the ledger are not named.</li>
 *   <li><b>Products</b> - any capitalised or product-shaped name must appear in the
 *       item's evidence. This is what catches "Kafka Streams" and "Aurora", which
 *       no fixed vocabulary lists.</li>
 *   <li><b>Claims</b> - "containerised", "real-time", "production", "scalable",
 *       "deployed" and the like need support: a technology in scope, or the words
 *       in the source itself.</li>
 *   <li><b>Numbers</b> - every figure already in the source, same kind (a
 *       percentage is not a multiplier). Years separately.</li>
 *   <li><b>Responsibility</b> - led, owned, architected, managed, expert... only
 *       where the source already says so.</li>
 *   <li><b>Qualifiers</b> - self-built, a year, production-style, helped, "rather
 *       than..." and the hedge on a number survive; see {@link QualifierGuard}.</li>
 *   <li><b>Stuffing</b> - no technology names tacked onto the end of the sentence.</li>
 *   <li><b>Meaning</b> - enough of the source's own content words survive that it
 *       is still recognisably the same accomplishment.</li>
 * </ol>
 * Any failure rejects the rewrite, and the source text is used instead. A false
 * rejection costs a better sentence; a false acceptance puts an invented claim on a
 * resume. The thresholds lean accordingly.
 */
public final class ResumeClaimValidator {

    private ResumeClaimValidator() {
    }

    public enum IssueType {
        INVALID_RESPONSE,
        UNKNOWN_SOURCE,
        EMPTY,
        UNSUPPORTED_TECHNOLOGY,
        PROHIBITED_TERM,
        UNSUPPORTED_PRODUCT,
        UNSUPPORTED_CLAIM,
        INVENTED_NUMBER,
        INVENTED_YEARS,
        INFLATED_RESPONSIBILITY,
        /** A word limiting the claim - self-built, a year, roughly - was removed. */
        QUALIFIER_LOST,
        /** Technology names tacked onto the end of the source sentence. */
        KEYWORD_STUFFING,
        MEANING_DRIFT
    }

    public record Issue(IssueType type, String detail) {
    }

    /**
     * @param retention         share of the source's content words kept
     * @param sourceMetrics     figures in the source text
     * @param sourceMetricsKept of those, how many the rewrite still states
     */
    public record Verdict(boolean pass, List<Issue> issues, List<String> warnings,
            double retention, int sourceMetrics, int sourceMetricsKept) {

        public long count(IssueType type) {
            return issues.stream().filter(i -> i.type() == type).count();
        }

        public static Verdict rejected(IssueType type, String detail) {
            return new Verdict(false, List.of(new Issue(type, detail)), List.of(), 0, 0, 0);
        }
    }

    static final double MIN_RETENTION_BULLET = 0.4;

    /**
     * Products outside {@link TechVocabulary} that a rewrite might reach for, checked
     * in any case. The capitalised-name check catches "Aurora"; this catches
     * "aurora", which a model writing in sentence case also produces.
     */
    private static final List<String> EXTRA_PRODUCTS = List.of(
            "aurora", "kafka streams", "ksqldb", "cloudwatch", "bigtable", "spanner",
            "firestore", "cosmos db", "supabase", "vercel", "heroku", "netlify", "splunk",
            "new relic", "pagerduty", "camunda", "bazel", "gradle", "circleci", "looker",
            "tableau", "power bi", "bedrock", "vertex ai", "openshift", "nomad", "rancher");

    private static final int CI = Pattern.CASE_INSENSITIVE;

    // ------------------------------------------------------------------
    // Re-framings: wording that is a fair description of a technology in scope
    // ------------------------------------------------------------------

    /**
     * Vocabulary terms a rewrite may introduce because something in scope is that
     * thing. Short and explicit, like {@code SkillGraph}: widening it is a
     * deliberate edit, not something a model gets to decide.
     */
    private static final Map<String, Set<String>> REFRAMED_TECHNOLOGY = Map.of(
            "event-driven", Set.of("kafka", "rabbitmq", "sqs", "sns", "kinesis", "pulsar",
                    "nats", "event-driven", "event-driven systems"),
            "microservices", Set.of("microservices"),
            "distributed systems", Set.of("distributed systems"),
            "websockets", Set.of("websockets", "websocket"));

    /**
     * Wording that asserts something about the work.
     *
     * @param techSupport any of these in scope supports it
     * @param textSupport or this in the text - the source text alone when strict
     * @param strict      claims of scale, deployment and production: only the
     *                    source sentence itself can license them
     */
    private record Claim(String name, Pattern pattern, Set<String> techSupport,
            Pattern textSupport, boolean strict) {
    }

    private static Claim claim(String name, String regex, Set<String> tech, String support,
            boolean strict) {
        return new Claim(name, Pattern.compile(regex, CI), tech, Pattern.compile(support, CI), strict);
    }

    private static final List<Claim> CLAIMS = List.of(
            claim("containers", "\\bcontaineri[sz](ed|ation)\\b|\\bcontainers?\\b",
                    Set.of("docker"), "containeri[sz]|container", false),
            claim("REST APIs", "\\brest(ful)?[- ]?(apis?|services|endpoints|interfaces)\\b",
                    Set.of("rest", "api", "fastapi", "flask", "spring boot", "rest apis"),
                    "\\brest|\\bapis?\\b", false),
            claim("real-time", "\\breal[- ]time\\b",
                    Set.of("real-time", "websockets", "websocket", "streaming"),
                    "real[- ]time", false),
            claim("asynchronous", "\\basynchronous(ly)?\\b|\\basync\\b",
                    Set.of("asynchronous", "kafka", "workers", "celery", "asyncio",
                            "asynchronous processing"), "\\basync", false),
            claim("streaming", "\\bstream(ing|s)?\\b",
                    Set.of("kafka", "websockets", "streaming", "kinesis", "flink"), "\\bstream", false),
            claim("caching", "\\bcach(e|es|ing|ed)\\b",
                    Set.of("redis", "memcached", "caching"), "\\bcach", false),
            claim("fault tolerance", "\\bfault[- ]toleran\\w*|\\bresilien\\w*|\\bhigh(ly)?[- ]available\\b",
                    Set.of("retries", "fault tolerance", "reliability"),
                    "retr|fail|fault|reliab|resilien", false),
            claim("monitoring", "\\bobservab\\w*|\\bmonitor\\w*|\\balerting\\b",
                    Set.of(), "monitor|observ|alert", false),
            claim("cloud", "\\bcloud\\b", Set.of("aws", "gcp", "azure"), "\\bcloud", false),
            claim("serverless", "\\bserverless\\b", Set.of("lambda"), "serverless|lambda", false),
            claim("testing", "\\b(unit|integration|automated|end[- ]to[- ]end) test(s|ing)?\\b"
                    + "|\\btest[- ]driven\\b",
                    Set.of("junit", "pytest", "mockito", "testing", "testcontainers"), "\\btest", false),
            claim("system design", "\\bsystems? design\\b|\\bsystem architecture\\b",
                    Set.of(), "architect|system design", true),
            claim("code review", "\\bcode reviews?\\b", Set.of(), "review", true),
            claim("on-call", "\\bon[- ]call\\b", Set.of(), "on[- ]call", true),
            claim("cross-functional", "\\bcross[- ]functional", Set.of(),
                    "cross[- ]functional|collaborat", true),
            // "production-style" is a qualifier, not a production claim, and must
            // not license one.
            claim("production", "\\bproduction\\b(?![- ](style|like))", Set.of(),
                    "\\bproduction\\b(?![- ](style|like))", true),
            claim("employment", "\\bprofessional(ly)?\\b|\\bcommercial(ly)?\\b|\\bfor clients?\\b"
                    + "|\\bclient[- ]facing\\b|\\bin industry\\b", Set.of(),
                    "professional|commercial|client|industry", true),
            claim("deployment", "\\bdeploy(ed|ing|ment|ments|s)?\\b", Set.of(), "\\bdeploy", true),
            claim("scale", "\\bscal(e|able|ability|ed|ing)\\b|\\bat scale\\b", Set.of(), "\\bscal", true),
            claim("throughput", "\\bhigh[- ](throughput|volume|traffic|availability|performance)\\b"
                    + "|\\blow[- ]latency\\b", Set.of(),
                    "throughput|volume|traffic|latency|faster|performance", true),
            claim("reach", "\\bmillions?\\b|\\benterprise\\b|\\bmission[- ]critical\\b"
                    + "|\\bcustomers?\\b", Set.of(), "million|enterprise|mission|customer", true));

    /** A responsibility word, and what in the source licenses it. */
    private record Inflation(String name, Pattern pattern, Pattern support) {
    }

    private static Inflation inflation(String name, String regex, String support) {
        return new Inflation(name, Pattern.compile(regex, CI), Pattern.compile(support, CI));
    }

    private static final List<Inflation> INFLATION = List.of(
            inflation("led", "\\b(led|leads|leading)\\b(?!\\s+to\\b)|\\blead\\b(?=\\s+(the|a|an|team|development|engineer))",
                    "\\b(led|lead|leading)\\b"),
            inflation("owned", "\\b(own|owned|owning|ownership|owner)\\b", "\\bown"),
            inflation("architected", "\\barchitect(ed|ing|s)?\\b", "\\barchitect"),
            inflation("designed the architecture",
                    "\\bdesign(ed|ing)?\\s+(the\\s+|its\\s+)?(overall\\s+|system\\s+|platform\\s+"
                            + "|end-to-end\\s+|backend\\s+)?architecture\\b",
                    "\\barchitect"),
            inflation("managed", "\\bmanag(ed|es|ing)\\b(?!\\s+(service|services|database|instance|cluster))",
                    "\\bmanag"),
            inflation("spearheaded", "\\bspearhead\\w*", "\\bspearhead"),
            inflation("directed", "\\b(headed|directed|directing|oversaw|oversee\\w*|supervis\\w*)\\b",
                    "\\b(headed|direct|overs|supervis)"),
            inflation("mentored", "\\bmentor\\w*", "\\bmentor"),
            inflation("drove", "\\b(drove|championed|pioneered)\\b", "\\b(drove|champion|pioneer)"),
            inflation("sole responsibility", "\\bend[- ]to[- ]end\\b|\\bsingle[- ]handedly\\b|\\bsole\\b"
                    + "|\\bresponsible for\\b", "end[- ]to[- ]end|single|sole|responsible"),
            inflation("team leadership", "\\bteam of\\b|\\bcross[- ]functional team\\b", "team of|team"),
            inflation("expertise", "\\b(expert|expertise|extensive(ly)?|seasoned|senior|advanced"
                    + "|deep (knowledge|expertise)|mastery|mastered|specialist|world[- ]class"
                    + "|proficient|proficiency)\\b",
                    "expert|extensive|seasoned|senior|advanced|deep|master|specialist|proficien"));

    // ------------------------------------------------------------------

    public static Verdict check(String rewrite, RewriteRequest request, Set<String> knownSourceIds) {
        if (request == null || request.sourceId() == null
                || !knownSourceIds.contains(request.sourceId())) {
            return Verdict.rejected(IssueType.UNKNOWN_SOURCE, "no source item '"
                    + (request == null ? null : request.sourceId()) + "'");
        }
        if (rewrite == null || rewrite.isBlank()) {
            return Verdict.rejected(IssueType.EMPTY, "the rewrite is empty");
        }
        EvidenceScope scope = request.scope();
        List<Issue> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> flagged = new HashSet<>();

        technologies(rewrite, scope, issues, flagged);
        prohibited(rewrite, request, issues, flagged);
        products(rewrite, scope, issues, flagged);
        claims(rewrite, scope, issues);
        int[] metrics = numbers(rewrite, scope, issues, warnings);
        years(rewrite, scope, issues);
        inflation(rewrite, scope, issues);
        QualifierGuard.lost(scope.sourceText(), rewrite)
                .forEach(detail -> issues.add(new Issue(IssueType.QUALIFIER_LOST, detail)));
        RewriteQuality.appendedSuffix(scope.sourceText(), rewrite)
                .ifPresent(suffix -> issues.add(new Issue(IssueType.KEYWORD_STUFFING,
                        "'" + suffix + "' is tacked onto the end of the sentence")));
        RewriteQuality.lowercaseNames(scope.sourceText(), rewrite)
                .forEach(name -> warnings.add("technology name in lower case: '" + name + "'"));

        double retention = RewriteMetrics.retention(scope.sourceText(), rewrite);
        if (retention < MIN_RETENTION_BULLET) {
            issues.add(new Issue(IssueType.MEANING_DRIFT, String.format(
                    "only %.0f%% of the source's content words survive; it may describe a "
                            + "different accomplishment", retention * 100)));
        }
        if (rewrite.length() > request.maxChars()) {
            warnings.add("longer than the limit (" + rewrite.length() + " > "
                    + request.maxChars() + " characters)");
        }
        return new Verdict(issues.isEmpty(), List.copyOf(issues), List.copyOf(warnings),
                retention, metrics[0], metrics[1]);
    }

    // ------------------------------------------------------------------

    private static void technologies(String rewrite, EvidenceScope scope, List<Issue> issues,
            Set<String> flagged) {
        for (String term : TechVocabulary.found(rewrite)) {
            if ("rest".equals(term)) {
                continue; // judged as the "REST APIs" claim
            }
            if (PostingRequirements.AMBIGUOUS.contains(term) && !capitalised(rewrite, term)) {
                continue;
            }
            String canonical = PostingRequirements.ALIASES.getOrDefault(term, term);
            if (supportsTechnology(scope, canonical) || supportsTechnology(scope, term)) {
                continue;
            }
            if (flagged.add(canonical)) {
                issues.add(new Issue(IssueType.UNSUPPORTED_TECHNOLOGY,
                        term + " is not in this item's evidence"));
            }
        }
        for (String product : EXTRA_PRODUCTS) {
            if (RewriteMetrics.wholeWord(rewrite, product)
                    && !RewriteMetrics.wholeWord(scope.supportText(), product)
                    && flagged.add(product)) {
                issues.add(new Issue(IssueType.UNSUPPORTED_PRODUCT,
                        product + " is not in this item's evidence"));
            }
        }
    }

    private static boolean supportsTechnology(EvidenceScope scope, String term) {
        return scope.terms().contains(term)
                || REFRAMED_TECHNOLOGY.getOrDefault(term, Set.of()).stream()
                        .anyMatch(scope.terms()::contains)
                || RewriteMetrics.wholeWord(scope.supportText(), term);
    }

    private static void prohibited(String rewrite, RewriteRequest request, List<Issue> issues,
            Set<String> flagged) {
        for (String term : request.prohibited()) {
            String canonical = PostingRequirements.canonicalSubject(term).toLowerCase(Locale.ROOT);
            if (flagged.contains(canonical) || flagged.contains(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (RewriteMetrics.mentions(rewrite, term)
                    && !RewriteMetrics.mentions(request.scope().supportText(), term)) {
                flagged.add(canonical);
                issues.add(new Issue(IssueType.PROHIBITED_TERM, term
                        + " is related to his work but not his, and the rewrite names it"));
            }
        }
    }

    /** Words that are capitalised in resumes and name no product. */
    private static final Set<String> GENERIC_CAPS = Set.of("AI", "ML", "API", "APIs", "REST",
            "RESTful", "HTTP", "HTTPS", "JSON", "SQL", "CRUD", "UI", "UX", "I/O", "IO", "CPU", "GPU");

    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+#/]*[A-Za-z0-9+#]|[A-Za-z0-9]");
    private static final Pattern NUMERIC = Pattern.compile("\\d[\\d,.]*[xX%]?");

    private static void products(String rewrite, EvidenceScope scope, List<Issue> issues,
            Set<String> flagged) {
        Matcher matcher = TOKEN.matcher(rewrite);
        while (matcher.find()) {
            String token = matcher.group();
            if (NUMERIC.matcher(token).matches()) {
                continue;
            }
            boolean capital = Character.isUpperCase(token.charAt(0));
            boolean digit = token.chars().anyMatch(Character::isDigit);
            boolean innerUpper = token.length() > 1
                    && token.substring(1).chars().anyMatch(Character::isUpperCase);
            if (!capital && !digit && !innerUpper) {
                continue;
            }
            boolean allCaps = token.length() > 1 && token.equals(token.toUpperCase(Locale.ROOT));
            if (sentenceStart(rewrite, matcher.start()) && !digit && !innerUpper && !allCaps) {
                continue;
            }
            if (GENERIC_CAPS.contains(token) || RewriteMetrics.wholeWord(scope.supportText(), token)
                    || (token.endsWith("s") && token.length() > 2
                            && RewriteMetrics.wholeWord(scope.supportText(),
                                    token.substring(0, token.length() - 1)))) {
                continue;
            }
            if (flagged.add(token.toLowerCase(Locale.ROOT))) {
                issues.add(new Issue(IssueType.UNSUPPORTED_PRODUCT,
                        token + " is not named anywhere in this item's evidence"));
            }
        }
    }

    private static boolean sentenceStart(String text, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i < 0 || ".!?:;(\"'“".indexOf(text.charAt(i)) >= 0;
    }

    private static void claims(String rewrite, EvidenceScope scope, List<Issue> issues) {
        for (Claim claim : CLAIMS) {
            Matcher matcher = claim.pattern().matcher(rewrite);
            if (!matcher.find()) {
                continue;
            }
            String support = claim.strict() ? scope.sourceText() : scope.supportText();
            boolean supported = claim.techSupport().stream().anyMatch(scope.terms()::contains)
                    || claim.textSupport().matcher(support).find();
            if (!supported) {
                issues.add(new Issue(IssueType.UNSUPPORTED_CLAIM, "'" + matcher.group()
                        + "' (" + claim.name() + ") is not supported by this item"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    enum NumberKind { PLAIN, PERCENT, MULTIPLIER }

    record Figure(double value, NumberKind kind, boolean hedged, String raw) {

        boolean sameAs(Figure other) {
            return Math.abs(value - other.value) < 1e-9 && kind == other.kind;
        }
    }

    private static final Pattern DIGITS = Pattern.compile(
            "(?<![\\w.])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?"
                    + "(?:\\s*(%|percent\\b|x\\b|×|times\\b|-?fold\\b))?", CI);

    private static final Map<String, Integer> WORD_VALUES = Map.ofEntries(
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
            Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16),
            Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19),
            Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("hundred", 100), Map.entry("thousand", 1000));

    private static final Pattern WORDS = Pattern.compile(
            "\\b(" + String.join("|", WORD_VALUES.keySet()) + ")(\\s*-?\\s*(fold|times))?\\b", CI);

    private static final Pattern TWICE = Pattern.compile("\\btwice\\b", CI);

    private static final Pattern HEDGE = Pattern.compile(
            "(approximately|approx\\.?|roughly|about|around|nearly|almost|over|more than|up to"
                    + "|some|~|≈)\\s*$", CI);

    static List<Figure> figures(String text) {
        List<Figure> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher digits = DIGITS.matcher(text);
        while (digits.find()) {
            double value = Double.parseDouble(digits.group(1).replace(",", "")
                    + (digits.group(2) == null ? "" : digits.group(2)));
            out.add(new Figure(value, kindOf(digits.group(3)), hedged(text, digits.start()),
                    digits.group().strip()));
        }
        Matcher words = WORDS.matcher(text);
        while (words.find()) {
            out.add(new Figure(WORD_VALUES.get(words.group(1).toLowerCase(Locale.ROOT)),
                    words.group(3) == null ? NumberKind.PLAIN : NumberKind.MULTIPLIER,
                    hedged(text, words.start()), words.group()));
        }
        Matcher twice = TWICE.matcher(text);
        while (twice.find()) {
            out.add(new Figure(2, NumberKind.MULTIPLIER, hedged(text, twice.start()), "twice"));
        }
        return out;
    }

    private static NumberKind kindOf(String suffix) {
        if (suffix == null) {
            return NumberKind.PLAIN;
        }
        String s = suffix.toLowerCase(Locale.ROOT);
        return s.equals("%") || s.startsWith("percent") ? NumberKind.PERCENT : NumberKind.MULTIPLIER;
    }

    private static boolean hedged(String text, int start) {
        return HEDGE.matcher(text.substring(Math.max(0, start - 20), start)).find();
    }

    /** @return figures in the source, and how many the rewrite kept */
    private static int[] numbers(String rewrite, EvidenceScope scope, List<Issue> issues,
            List<String> warnings) {
        List<Figure> allowed = figures(scope.supportText());
        List<Figure> original = figures(scope.sourceText());
        List<Figure> used = figures(rewrite);

        for (Figure figure : used) {
            if (allowed.stream().noneMatch(a -> a.sameAs(figure))) {
                issues.add(new Issue(IssueType.INVENTED_NUMBER,
                        "'" + figure.raw() + "' is not a figure the source states"));
            }
        }
        int kept = 0;
        for (Figure figure : original) {
            Optional<Figure> match = used.stream().filter(u -> u.sameAs(figure)).findFirst();
            if (match.isEmpty()) {
                warnings.add("metric dropped: '" + figure.raw() + "'");
            } else {
                kept++;
                if (figure.hedged() && !match.get().hedged()) {
                    // "roughly fourfold" as "fourfold" claims a precision he
                    // never measured. A failure, not a warning.
                    issues.add(new Issue(IssueType.QUALIFIER_LOST, "the source gives '"
                            + figure.raw() + "' as an approximation; the rewrite states it exactly"));
                }
            }
        }
        return new int[] {original.size(), kept};
    }

    private static final Pattern YEARS = Pattern.compile(
            "\\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\+?\\s+"
                    + "(?:full\\s+)?(years?|yrs?)\\b", CI);

    private static void years(String rewrite, EvidenceScope scope, List<Issue> issues) {
        Set<String> allowed = yearValues(scope.sourceText());
        for (String value : yearValues(rewrite)) {
            if (!allowed.contains(value)) {
                issues.add(new Issue(IssueType.INVENTED_YEARS,
                        "states " + value + " year(s) of experience, which the source does not"));
            }
        }
    }

    private static Set<String> yearValues(String text) {
        Set<String> values = new HashSet<>();
        Matcher matcher = YEARS.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String raw = matcher.group(1).toLowerCase(Locale.ROOT);
            values.add(switch (raw) {
                case "a", "an", "one" -> "1";
                default -> WORD_VALUES.containsKey(raw) ? String.valueOf(WORD_VALUES.get(raw)) : raw;
            });
        }
        return values;
    }

    private static void inflation(String rewrite, EvidenceScope scope, List<Issue> issues) {
        for (Inflation inflation : INFLATION) {
            Matcher matcher = inflation.pattern().matcher(rewrite);
            if (matcher.find() && !inflation.support().matcher(scope.sourceText()).find()) {
                issues.add(new Issue(IssueType.INFLATED_RESPONSIBILITY, "'" + matcher.group()
                        + "' (" + inflation.name() + ") - the source makes no such claim"));
            }
        }
    }

    private static boolean capitalised(String text, String term) {
        Matcher matcher = Pattern.compile("(?<![\\w+#.])" + Pattern.quote(term) + "(?![\\w+#])", CI)
                .matcher(text);
        while (matcher.find()) {
            if (Character.isUpperCase(text.charAt(matcher.start()))) {
                return true;
            }
        }
        return false;
    }
}
