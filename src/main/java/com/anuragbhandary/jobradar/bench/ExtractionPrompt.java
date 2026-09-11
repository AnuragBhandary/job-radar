package com.anuragbhandary.jobradar.bench;

import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The requirement-extraction task every model is given, word for word the same.
 *
 * <p>The schema has no id field. Ids are derived in Java from the term, so a
 * model cannot collide two requirements or give one a misleading name; it only
 * proposes what the posting asks for and the words it used.
 */
public final class ExtractionPrompt {

    private ExtractionPrompt() {
    }

    public static final int MAX_REQUIREMENTS = 40;

    public static String system() {
        return """
                You extract the requirements from one job posting.

                Reply with JSON only:
                {"requirements":[{"term":"...","category":"...","importance":"...","quote":"..."}]}

                Rules:
                1. "quote" is copied exactly, character for character, from the posting:
                   one contiguous span of 3 to 30 words. Do not paraphrase, fix spelling,
                   shorten the middle, or join separate sentences. If you cannot quote the
                   posting for a requirement, leave the requirement out.
                2. "term" is a short name for one requirement: "Kafka", "REST API design",
                   "distributed systems", "cross-functional collaboration". One requirement
                   per term. When one sentence names several technologies, emit one
                   requirement per technology, each with that same quote.
                3. "importance":
                   REQUIRED  - stated as required, minimum, must-have, or listed under
                               requirements, qualifications or "what you need".
                   PREFERRED - nice to have, a plus, a bonus, preferred.
                   SIGNAL    - mentioned about the work (stack, responsibilities, team)
                               but not stated as a requirement.
                4. "category": one of %s.
                5. Leave out benefits, perks, salary, visa, location, legal and equal
                   opportunity text, and how to apply.
                6. At most %d requirements, most important first.
                """.formatted(String.join(", ", names(RequirementCategory.values())),
                MAX_REQUIREMENTS);
    }

    public static String user(String title, String description) {
        return "TITLE: " + (title == null ? "" : title)
                + "\n\nPOSTING:\n" + (description == null ? "" : description);
    }

    /** The reply shape, as a JSON schema for Ollama's {@code format}. */
    public static Map<String, Object> schema() {
        Map<String, Object> item = Map.of(
                "type", "object",
                "properties", Map.of(
                        "term", Map.of("type", "string"),
                        "category", Map.of("type", "string",
                                "enum", names(RequirementCategory.values())),
                        "importance", Map.of("type", "string",
                                "enum", names(RequirementImportance.values())),
                        "quote", Map.of("type", "string")),
                "required", List.of("term", "category", "importance", "quote"));
        return Map.of(
                "type", "object",
                "properties", Map.of("requirements", Map.of("type", "array", "items", item)),
                "required", List.of("requirements"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
