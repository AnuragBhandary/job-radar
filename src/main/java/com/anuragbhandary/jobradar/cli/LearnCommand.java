package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.AnswerBank;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code learn} - the questions that have stopped applications, as YAML to paste.
 *
 * <p>Closes the loop that {@code applications --status=NEEDS_HUMAN} only half
 * closed: that printed the blockers, and left the reader to translate each one
 * into a profile key by hand, guessing how short to make it so that substring
 * matching still catches the next board's wording.
 *
 * <p>{@code --write} appends straight to {@code applicant.yml}. It appends and
 * never rewrites, and it refuses if it cannot find the {@code extra-answers} block
 * - that file is hand-maintained and holds every personal detail the tool has.
 */
@Component
public class LearnCommand {

    private static final String ANCHOR = "extra-answers:";

    private final ApplicationAttemptRepository attempts;

    public LearnCommand(ApplicationAttemptRepository attempts) {
        this.attempts = attempts;
    }

    public void run(Map<String, String> options) {
        List<AnswerBank.Suggestion> suggestions =
                AnswerBank.suggest(attempts.findAll());

        if (suggestions.isEmpty()) {
            System.out.println("\nNo unanswered questions recorded yet. "
                    + "Run `apply --posting-id=N` on a few postings first.");
            return;
        }

        long blocking = suggestions.stream()
                .filter(AnswerBank.Suggestion::required).count();
        System.out.printf("%n%d distinct question(s) the profile could not answer; "
                + "%d of them blocked a form.%n%n", suggestions.size(), blocking);

        String yaml = AnswerBank.toYaml(suggestions);
        System.out.println("Paste under `job-radar.applicant.extra-answers`:\n");
        System.out.println(yaml);

        if ("true".equals(options.get("write"))) {
            append(yaml, options.get("profile"));
        } else {
            System.out.println("`learn --write` appends this to applicant.yml for you.");
        }
    }

    /**
     * Appends the block directly under {@code extra-answers:}.
     *
     * <p>Inserted at the top of the block rather than the bottom: first match wins
     * in the mapper, so a new, more specific key placed above an older general one
     * takes effect. Appending at the end would leave a new entry shadowed by
     * whatever was already there.
     */
    private void append(String yaml, String profileOverride) {
        Path profile = Path.of(profileOverride != null ? profileOverride
                : System.getProperty("user.home") + "/.config/job-radar/applicant.yml");

        try {
            if (!Files.exists(profile)) {
                System.out.println("No profile at " + profile + " - nothing written.");
                return;
            }
            String content = Files.readString(profile, StandardCharsets.UTF_8);
            int anchor = content.indexOf(ANCHOR);
            if (anchor < 0) {
                System.out.println("Could not find `" + ANCHOR + "` in " + profile
                        + " - nothing written. Paste the block above by hand.");
                return;
            }
            int lineEnd = content.indexOf('\n', anchor);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }

            String updated = content.substring(0, lineEnd + 1)
                    + "\n      # --- added by `learn` on " + java.time.LocalDate.now()
                    + " - fill in the blanks ---\n"
                    + yaml + "\n"
                    + content.substring(lineEnd + 1);

            Path backup = profile.resolveSibling("applicant.yml.bak");
            Files.writeString(backup, content, StandardCharsets.UTF_8);
            Files.writeString(profile, updated, StandardCharsets.UTF_8);
            System.out.println("Appended to " + profile
                    + "\nPrevious version saved as " + backup
                    + "\nThe answers are blank - fill them in before the next run.");
        } catch (IOException e) {
            System.out.println("Could not write the profile: " + e.getMessage());
        }
    }
}
