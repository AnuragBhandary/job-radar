package com.anuragbhandary.jobradar.filter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds an engineering posting that is not a software job.
 *
 * <p>The title filter keeps anything called an engineer that is not excluded by
 * name, which lets in propulsion, flight control, test-bench and hardware roles
 * from the German aggregators: on 2026-09-28 three of the eight candidates were
 * aerospace roles whose descriptions named MATLAB, Simulink or "python scripts"
 * and nothing else a software engineer works with. A software job names its
 * tools, so a full description that names fewer than two of them is rejected.
 *
 * <p>Only when the title does not say software either. Checked against the real
 * database on 2026-09-28, the description test alone rejected 125 postings,
 * among them Amazon SDE roles, OpenAI and Canonical "Software Engineer" postings
 * and two roles already applied to: big employers often describe the team and
 * never list a tool. A title that says software, developer, backend, data and so
 * on is taken at its word.
 *
 * <p>Short descriptions are left alone: some board summaries are a few lines
 * long, and saying little is not evidence of anything.
 */
final class SoftwareSignal {

    /** Below this, a description is a summary rather than the posting. */
    static final int MIN_DESCRIPTION_CHARS = 800;

    static final int MIN_TERMS = 2;

    /**
     * Tools and concepts of software work. Not "software" itself: aerospace and
     * hardware postings use it too ("software for safety-critical applications").
     */
    private static final List<String> TERMS = List.of(
            "python", "java", "golang", "c++", "c#", ".net", "kotlin", "scala", "rust", "ruby",
            "php", "typescript", "javascript", "node.js", "nodejs", "sql", "postgres",
            "postgresql", "mysql", "mongodb", "redis", "kafka", "rabbitmq", "docker",
            "kubernetes", "aws", "gcp", "azure", "rest", "api", "apis", "microservices",
            "backend", "back-end", "linux", "git", "ci/cd", "spring", "django", "fastapi",
            "flask", "graphql", "grpc", "terraform", "spark", "llm", "llms",
            "distributed systems", "data structures", "algorithms");

    private static final List<Pattern> PATTERNS = TERMS.stream()
            .map(t -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(t)
                    + "(?![\\p{L}\\p{N}+#])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();

    /** Title words that already say the job is software work. */
    private static final Pattern SOFTWARE_TITLE = Pattern.compile(
            "(?<![\\p{L}])(?:software|sde|sdet|developer|develop|programmer|backend|back-end"
                    + "|back end|platform|infrastructure|infra|data|ml|machine learning|ai|llm"
                    + "|reliability|sre|devops|cloud|security|systems?|api|application|web"
                    + "|mobile|android|ios|python|java|golang|kernel|database|compiler|research"
                    + "|full[- ]?stack|frontend|front-end|qa|automation test"
                    // Analyst roles are wanted for the data resume even when the
                    // posting names only Excel and a BI tool (2026-09-29).
                    + "|analyst|analytics|bi|business intelligence|etl|sql)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * "Engineer", "Engineer II", "Associate Engineer": says nothing either way,
     * and India's captive centres (Target's Bangalore "Engineer") use it for
     * software roles.
     */
    private static final Pattern BARE_TITLE = Pattern.compile(
            "^\\W*(?:junior|associate|graduate|entry[- ]level)?\\s*engineer(?:ing)?"
                    + "\\s*(?:i{1,3}|[1-3])?\\W*$", Pattern.CASE_INSENSITIVE);

    /** Building, plant and hotel upkeep: what a non-software "Engineer" does. */
    private static final Pattern MAINTENANCE = Pattern.compile(
            "\\b(?:plumbing|plumber|hvac|carpentry|drywall|boilers?|preventive\\s+maintenance"
                    + "|guest\\s+(?:repair|room)s?|shut-?off\\s+valves?|refrigeration|housekeeping"
                    + "|kitchen\\s+equipment|mechanical\\s+room)\\b",
            Pattern.CASE_INSENSITIVE);

    private SoftwareSignal() {
    }

    /** Why the posting is not a software job, or empty. */
    static Optional<String> missing(String title, String description) {
        if (title != null && SOFTWARE_TITLE.matcher(title).find()) {
            return Optional.empty();
        }
        if (title != null && BARE_TITLE.matcher(title).matches()) {
            // Still trusted when the description says nothing, as at Target's
            // captive centres. Only building and hotel maintenance is caught:
            // Marriott's "Engineer I" (2026-10-01) was plumbing, drywall and
            // kitchen equipment, and named no software tool.
            if (description != null && termsIn(description).isEmpty()) {
                java.util.regex.Matcher m = MAINTENANCE.matcher(description);
                if (m.find()) {
                    return Optional.of("not a software role: a bare engineer title over "
                            + "maintenance work (\"" + m.group() + "\")");
                }
            }
            return Optional.empty();
        }
        return missing(description);
    }

    /** The description test alone. */
    static Optional<String> missing(String description) {
        if (description == null || description.length() < MIN_DESCRIPTION_CHARS) {
            return Optional.empty();
        }
        Set<String> found = termsIn(description);
        if (found.size() >= MIN_TERMS) {
            return Optional.empty();
        }
        return Optional.of("not a software role: description names "
                + (found.isEmpty() ? "no software tools" : "only " + String.join(", ", found)));
    }

    static Set<String> termsIn(String description) {
        Set<String> found = new LinkedHashSet<>();
        String text = description.toLowerCase(Locale.ROOT);
        for (int i = 0; i < TERMS.size(); i++) {
            if (PATTERNS.get(i).matcher(text).find()) {
                found.add(TERMS.get(i));
            }
        }
        return found;
    }
}
