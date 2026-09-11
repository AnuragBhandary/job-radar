package com.anuragbhandary.jobradar.apply.resume.rewrite;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the model is told for one rewrite.
 *
 * <p>Small on purpose: the requirements this item supports, the ones it must not
 * name, its own technologies and its own sentence. Not the posting, not the rest
 * of the resume - context the model does not need is context it can borrow facts
 * from.
 */
public final class RewritePrompt {

    private RewritePrompt() {
    }

    public static String system(boolean summary) {
        if (summary) {
            return """
                    You rewrite the professional summary at the top of one resume so it
                    speaks to one job. The facts are fixed. You only choose the words and
                    the emphasis.

                    Rules, in order of importance:
                    1. Stay true to the SOURCE summary. You may reorder emphasis and use
                       the job's wording, but every claim must already be in the source
                       summary or in ALLOWED TECHNOLOGIES.
                    2. Experience: keep the length of experience exactly as the source
                       states it. Never state any other number of years.
                    3. Technologies: name only technologies in ALLOWED TECHNOLOGIES. Never
                       name anything under DO NOT CLAIM or RELATED BUT NOT HIS.
                    4. No new numbers, no seniority ("senior", "lead", "expert",
                       "extensive", "deep expertise"), no claims of production scale or
                       ownership the source does not already make.
                    5. Two or three sentences, no "I", sentence case, no more than the
                       stated limit.

                    Reply with JSON only: {"sourceId":"<the sourceId given>","rewrittenText":"..."}
                    """;
        }
        return """
                You rewrite one resume bullet so it speaks to one job. The facts are fixed.
                You only choose the words.

                Rules, in order of importance:
                1. Same accomplishment. Keep what was built and what it did. Do not merge
                   in other work, and do not turn it into a different achievement because
                   the job would like that better.
                2. Technologies: name only technologies in ALLOWED TECHNOLOGIES. Never name
                   anything under DO NOT CLAIM or RELATED BUT NOT HIS, and add no other
                   product, tool, framework, cloud service, platform or database.
                3. Numbers: keep every number exactly as the source gives it, including its
                   hedge ("approximately", "roughly"). Add no new number, percentage,
                   multiplier or count.
                4. Strength: keep the source's verb strength. Do not add led, owned,
                   managed, architected, spearheaded, mentored, production, deployed,
                   scalable, at scale, expert, or any claim of seniority, ownership, scale
                   or deployment the source does not already make.
                5. You may use the job's own terminology where it describes exactly what
                   the source says: Kafka messaging can be "event-driven", Docker can be
                   "containerised", REST endpoints can be "REST APIs".
                6. One sentence, past tense, no "I", sentence case (do not capitalise
                   ordinary words), no more than the stated limit. Shorter is better.
                7. If the source already reads well for this job, return it unchanged.

                Reply with JSON only: {"sourceId":"<the sourceId given>","rewrittenText":"..."}
                """;
    }

    public static String user(RewriteRequest request, String jobTitle) {
        StringBuilder out = new StringBuilder("JOB: ").append(jobTitle == null ? "" : jobTitle)
                .append("\n\nWHAT THE JOB ASKS FOR THAT THIS SOURCE SUPPORTS\n");
        if (request.targets().isEmpty()) {
            out.append("(nothing specific - improve clarity only)\n");
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
        out.append("\nALLOWED TECHNOLOGIES: ")
                .append(request.scope().terms().stream().sorted().collect(Collectors.joining(", ")))
                .append("\n\nSOURCE (sourceId = ").append(request.sourceId()).append(")\n")
                .append(request.originalText())
                .append("\n\nLimit: ").append(request.maxChars()).append(" characters.");
        return out.toString();
    }

    public static Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sourceId", Map.of("type", "string"),
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
