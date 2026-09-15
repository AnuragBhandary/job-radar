package com.anuragbhandary.jobradar.apply.resume;

import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Takes the duplicate career claims out of applicant.yml, once the evidence bank is
 * confirmed to hold every one of them.
 *
 * <p>Removes {@code bullets:} under each job and the whole {@code projects:} block
 * of {@code job-radar.resume}, and nothing else. It edits the text rather than
 * re-serialising the YAML, so every comment, every other key and the file's own
 * layout survive.
 *
 * <p>Refuses rather than loses anything:
 * <ul>
 *   <li>every bullet removed must be the claim or an approved variant of an item
 *       under the bank source of the same name;</li>
 *   <li>every project's printed stack line must be what the bank prints;</li>
 *   <li>the edited file is parsed again and must equal the original with exactly
 *       those keys removed - one character of difference anywhere else and nothing is
 *       written.</li>
 * </ul>
 */
public final class ProfileMigration {

    private ProfileMigration() {
    }

    /**
     * @param text     the edited file; the original when nothing changed
     * @param removed  one line per removed bullet or project, for the report
     * @param problems why it refused; empty when it did not
     */
    public record Result(String text, boolean changed, List<String> removed, List<String> problems) {

        public Result {
            removed = List.copyOf(removed);
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+):(\\s*(#.*)?|\\s+.*)$");

    static final String NOTE = "# Projects - their names, stack lines and bullets - and every bullet under "
            + "a job live in the evidence bank (evidence.yml).";

    public static Result migrate(String yaml, EvidenceBank bank) {
        Object tree;
        try {
            tree = load(yaml);
        } catch (YAMLException e) {
            return refused("applicant.yml is not valid YAML: " + firstLine(e.getMessage()));
        }
        Map<String, Object> resume = map(map(tree).get("job-radar")) == null ? null
                : map(map(map(tree).get("job-radar")).get("resume"));
        if (resume == null) {
            return new Result(yaml, false, List.of(), List.of());
        }

        List<String> problems = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map<String, Object> job : maps(resume.get("experience"))) {
            String company = string(job.get("company"));
            List<Map<String, Object>> bullets = maps(job.get("bullets"));
            if (bullets.isEmpty()) {
                continue;
            }
            Optional<EvidenceSource> source = bank.sourceNamed(company, EvidenceSource.Kind.EMPLOYMENT);
            for (Map<String, Object> bullet : bullets) {
                String text = string(bullet.get("text"));
                if (source.isEmpty() || !approved(bank, source.get(), text)) {
                    problems.add("the evidence bank has no approved item under '" + company
                            + "' saying \"" + shorten(text) + "\" - add it first, or it would be lost");
                } else {
                    removed.add("bullet under " + company + ": " + shorten(text));
                }
            }
        }
        for (Map<String, Object> project : maps(resume.get("projects"))) {
            String name = string(project.get("name"));
            Optional<EvidenceSource> source = bank.sourceNamed(name, EvidenceSource.Kind.PROJECT);
            if (source.isEmpty()) {
                problems.add("the evidence bank has no project source named '" + name + "'");
                continue;
            }
            String stack = string(project.get("stack"));
            if (!stack.isBlank() && !flat(stack).equals(flat(source.get().displayStack()))) {
                problems.add("project '" + name + "' prints its stack as \"" + stack
                        + "\" and the bank as \"" + source.get().displayStack() + "\" - add stack-line: \""
                        + stack + "\" to source '" + source.get().id() + "' first");
            }
            for (Map<String, Object> bullet : maps(project.get("bullets"))) {
                String text = string(bullet.get("text"));
                if (!approved(bank, source.get(), text)) {
                    problems.add("the evidence bank has no approved item under '" + name
                            + "' saying \"" + shorten(text) + "\" - add it first, or it would be lost");
                }
            }
            removed.add("project " + name);
        }
        if (!problems.isEmpty()) {
            return new Result(yaml, false, List.of(), problems);
        }
        if (removed.isEmpty()) {
            return new Result(yaml, false, List.of(), List.of());
        }

        String edited = edit(yaml);
        Object after;
        try {
            after = load(edited);
        } catch (YAMLException e) {
            return refused("the edited file would not parse, so nothing was written: " + firstLine(e.getMessage()));
        }
        Object expected = withoutClaims(copy(tree));
        if (!Objects.equals(after, expected)) {
            return refused("the edit would have changed more than the bullets and projects, "
                    + "so nothing was written - remove them by hand");
        }
        return new Result(edited, true, removed, List.of());
    }

    // ------------------------------------------------------------------
    // The text edit
    // ------------------------------------------------------------------

    static String edit(String yaml) {
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));
        int resumeAt = findKey(lines, 0, lines.size(), "resume", -1);
        if (resumeAt < 0) {
            return yaml;
        }
        int resumeIndent = indent(lines.get(resumeAt));
        int resumeEnd = blockEnd(lines, resumeAt, resumeIndent);
        int child = childIndent(lines, resumeAt, resumeEnd);

        int projectsAt = findKey(lines, resumeAt + 1, resumeEnd, "projects", child);
        if (projectsAt >= 0) {
            int end = blockEnd(lines, projectsAt, child);
            lines.subList(projectsAt, end).clear();
            lines.add(projectsAt, " ".repeat(child) + NOTE);
            resumeEnd = blockEnd(lines, resumeAt, resumeIndent);
        }

        int experienceAt = findKey(lines, resumeAt + 1, resumeEnd, "experience", child);
        if (experienceAt >= 0) {
            int end = blockEnd(lines, experienceAt, child);
            List<int[]> ranges = new ArrayList<>();
            for (int i = experienceAt + 1; i < end; i++) {
                Matcher key = KEY.matcher(lines.get(i).replaceFirst("^(\\s*)- ", "$1  "));
                if (key.matches() && "bullets".equals(key.group(2))) {
                    int bulletIndent = indent(lines.get(i).replaceFirst("^(\\s*)- ", "$1  "));
                    ranges.add(new int[] {i, blockEnd(lines, i, bulletIndent)});
                }
            }
            for (int r = ranges.size() - 1; r >= 0; r--) {
                lines.subList(ranges.get(r)[0], ranges.get(r)[1]).clear();
            }
        }
        return String.join("\n", lines);
    }

    /** The first line at or after {@code from} that is this key, at this indent (-1: any). */
    private static int findKey(List<String> lines, int from, int to, String key, int atIndent) {
        for (int i = from; i < to; i++) {
            Matcher matcher = KEY.matcher(lines.get(i));
            if (matcher.matches() && key.equals(matcher.group(2))
                    && (atIndent < 0 || matcher.group(1).length() == atIndent)) {
                return i;
            }
        }
        return -1;
    }

    /** One past the last line belonging to the key on {@code start}; trailing blank lines are left. */
    private static int blockEnd(List<String> lines, int start, int keyIndent) {
        int last = start;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (indent(line) <= keyIndent) {
                break;
            }
            last = i;
        }
        return last + 1;
    }

    private static int childIndent(List<String> lines, int start, int end) {
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.strip().startsWith("#")) {
                return indent(line);
            }
        }
        return indent(lines.get(start)) + 2;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    private static boolean approved(EvidenceBank bank, EvidenceSource source, String text) {
        String wanted = flat(text);
        for (EvidenceItem item : bank.itemsFrom(source.id())) {
            if (item.approvedTexts().stream().anyMatch(t -> flat(t).equals(wanted))) {
                return true;
            }
        }
        return false;
    }

    /** The original tree with exactly the migrated keys removed. */
    @SuppressWarnings("unchecked")
    private static Object withoutClaims(Object tree) {
        Map<String, Object> resume = map(map(map(tree).get("job-radar")).get("resume"));
        resume.remove("projects");
        for (Object job : resume.get("experience") instanceof List<?> list ? list : List.of()) {
            if (job instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).remove("bullets");
            }
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(copy(v)));
            return out;
        }
        return node;
    }

    private static Object load(String yaml) {
        return copy(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            list.forEach(item -> {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            });
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String flat(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 80 ? flat : flat.substring(0, 79) + "…";
    }

    private static Result refused(String reason) {
        return new Result(null, false, List.of(), List.of(reason));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int cut = message.indexOf('\n');
        return (cut < 0 ? message : message.substring(0, cut)).strip();
    }
}
