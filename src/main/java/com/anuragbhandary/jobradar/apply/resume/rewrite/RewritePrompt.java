package com.anuragbhandary.jobradar.apply.resume.rewrite;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the model is told for one bullet rewrite.
 *
 * <p>Small on purpose: the requirements this bullet supports, the ones it must not
 * name, its own technologies and its own sentence. Not the posting, not the rest
 * of the resume - context the model does not need is context it can borrow facts
 * from. There is no summary prompt; see {@link RewritePlanner}.
 */
public final class RewritePrompt {

    private RewritePrompt() {
    }

    public static String system() {
        return """
                You rewrite one resume bullet so it speaks to one job. The facts are fixed.
                You only choose the words.

                Rules, in order of importance:
                1. Same accomplishment. Keep what was built and what it did. Do not merge
                   in other work, and do not turn it into a different achievement.
                2. Keep every qualifier that limits the claim: self-built, personal,
                   production-style, prototype, helped, contributed, a year, early-stage,
                   "rather than ...", and every "approximately" or "roughly" attached to a
                   number. Removing one changes the facts.
                3. Technologies: the sentence's own technologies are under THIS SENTENCE.
                   Those under ALSO TRUE may be named only where they make the sentence
                   clearer. Never name anything under DO NOT CLAIM or RELATED BUT NOT HIS,
                   and add no other product, tool, framework, cloud service or database.
                4. Numbers: keep every number exactly as the source gives it. Add no new
                   number, percentage, multiplier or count.
                5. Strength: keep the source's verb strength. Do not add led, owned,
                   managed, architected, spearheaded, mentored, production, professional,
                   deployed, scalable, at scale, expert, or any claim of seniority,
                   ownership, scale, deployment or employment the source does not make.
                6. Relevance: where the job's own terminology describes exactly what the
                   source says, prefer it (Kafka messaging -> event-driven; Docker ->
                   containerised; REST endpoints -> REST APIs). Do not add a technology
                   name just to mention it, and never append "using X" or "with X" to the
                   end of the sentence.
                7. One sentence, past tense, no "I". Ordinary words in lower case;
                   technology names in their usual capitalisation (Python, Docker, REST
                   APIs). No more than the stated limit.
                8. If the source already reads well for this job, return it unchanged.

                Reply with JSON only: {"sourceId":"<the sourceId given>","rewrittenText":"..."}
                """;
    }

    public static String user(RewriteRequest request, String jobTitle) {
        StringBuilder out = new StringBuilder("JOB: ").append(jobTitle == null ? "" : jobTitle)
                .append("\n\nWHAT THE JOB ASKS FOR THAT THIS SENTENCE SUPPORTS\n");
        if (request.targets().isEmpty()) {
            out.append("(nothing specific - improve clarity only, or return it unchanged)\n");
        }
        for (RewriteRequest.Target target : request.targets()) {
            out.append("- ").append(target.term()).append(" (").append(target.importance())
                    .append("): \"").append(shorten(target.quote(), 160)).append("\"\n");
        }
        if (!request.adjacent().isEmpty()) {
            out.append("\nRELATED BUT NOT HIS - never name these; you may emphasise the work after the arrow\n");
            for (RewriteRequest.Adjacent adjacent : request.adjacent()) {
                out.append("- ").append(adjacent.term()).append(" -> ")
                        .append(String.join(", ", adjacent.via())).append('\n');
            }
        }
        if (!request.prohibited().isEmpty()) {
            out.append("\nDO NOT CLAIM: ").append(String.join(", ", request.prohibited())).append('\n');
        }
        out.append("\nTHIS SENTENCE'S TECHNOLOGIES: ")
                .append(request.scope().primaryTerms().isEmpty() ? "(none named)"
                        : request.scope().primaryTerms().stream().sorted()
                                .collect(Collectors.joining(", ")));
        if (!request.scope().secondaryTerms().isEmpty()) {
            out.append("\nALSO TRUE OF THIS WORK (name only where it makes the sentence clearer): ")
                    .append(request.scope().secondaryTerms().entrySet().stream()
                            .map(e -> e.getKey() + " (" + e.getValue() + ")")
                            .collect(Collectors.joining("; ")));
        }
        out.append("\n\nSOURCE (sourceId = ").append(request.sourceId()).append(")\n")
                .append(request.originalText())
                .append("\n\nLimit: ").append(request.maxChars()).append(" characters.");
        return out.toString();
    }

    /**
     * The reply shape, with the source id pinned.
     *
     * <p>{@code sourceId} is an enum of exactly one value, so Ollama's constrained
     * decoding cannot emit any other string. The first benchmark lost two rewrites
     * to a model copying an eight-character hash with one digit wrong; asking it to
     * copy carefully is a request, and a schema is a rule. The parser still checks,
     * because a schema is only as good as the server enforcing it.
     */
    public static Map<String, Object> schema(String sourceId) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sourceId", Map.of("type", "string", "enum", List.of(sourceId)),
                        "rewrittenText", Map.of("type", "string")),
                "required", List.of("sourceId", "rewrittenText"));
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
