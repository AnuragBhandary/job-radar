package com.anuragbhandary.jobradar.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The configured answers, plus the ones added since the process started.
 *
 * <p>Exists because of an ordering problem. {@link ApplicantProfile} is bound
 * once at startup, so an answer written to {@code applicant.yml} does not reach
 * the form filler until the next restart. That turns "answer the question that
 * blocked this application" into "answer it, quit, start again, apply again",
 * and a loop with a restart in the middle is a loop nobody closes.
 *
 * <p>So an answer is written to the file <em>and</em> held here, and the mapper
 * reads this rather than the profile directly. The file is still the record; this
 * is only what stops the next twenty minutes from needing a bounce.
 *
 * <p>Additions come first. First match wins in the mapper, so a new and usually
 * more specific answer has to sit above the general one it is refining -
 * the same reasoning that makes {@code learn --write} insert at the top of the
 * block rather than the bottom.
 */
@Service
public class AnswerStore {

    private static final Logger log = LoggerFactory.getLogger(AnswerStore.class);
    private static final String ANCHOR = "extra-answers:";

    private final ApplicantProfile profile;
    private final List<ApplicantProfile.ExtraAnswer> added = new CopyOnWriteArrayList<>();
    private final Path profilePath;

    @org.springframework.beans.factory.annotation.Autowired
    public AnswerStore(ApplicantProfile profile) {
        this(profile, Path.of(System.getProperty("user.home"),
                ".config/job-radar/applicant.yml"));
    }

    /** Visible for tests and for anything that must not touch the real profile. */
    public AnswerStore(ApplicantProfile profile, Path profilePath) {
        this.profile = profile;
        this.profilePath = profilePath;
    }

    /** Everything the mapper should try, newest first. */
    public List<ApplicantProfile.ExtraAnswer> all() {
        List<ApplicantProfile.ExtraAnswer> answers = new ArrayList<>(added);
        if (profile != null && profile.extraAnswers() != null) {
            answers.addAll(profile.extraAnswers());
        }
        return answers;
    }

    /** True when something already answers this question, so it is no longer open. */
    public boolean answers(String match) {
        String needle = match == null ? "" : match.toLowerCase(java.util.Locale.ROOT).trim();
        return !needle.isBlank() && all().stream()
                .anyMatch(entry -> entry.match() != null
                        && entry.match().toLowerCase(java.util.Locale.ROOT).trim().equals(needle));
    }

    /**
     * Records an answer: in memory now, and in the profile for next time.
     *
     * @return what happened, for showing to whoever pressed the button
     * @throws IllegalArgumentException when either half is blank. An empty answer
     *                                  would match the question and then fill the
     *                                  field with nothing, which is worse than
     *                                  leaving it blocked - a blocked form stops
     *                                  and says so.
     */
    public String remember(String match, String answer) {
        if (match == null || match.isBlank()) {
            throw new IllegalArgumentException("A question is needed.");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException(
                    "An answer is needed. A blank one would match the question and then "
                            + "fill the field with nothing.");
        }
        String key = match.trim();
        String value = answer.trim();
        added.removeIf(entry -> key.equalsIgnoreCase(entry.match()));
        added.addFirst(new ApplicantProfile.ExtraAnswer(key, value));

        String written = writeToProfile(key, value);
        return "Saved. " + written + " It applies to the next form without a restart.";
    }

    /**
     * Appends one entry directly under the {@code extra-answers:} line.
     *
     * <p>Appends and never rewrites, and backs the file up first: this file is
     * hand-maintained and holds every personal detail the tool has, so a
     * clever edit that loses a line is not a trade worth making.
     */
    private String writeToProfile(String match, String answer) {
        try {
            if (!Files.exists(profilePath)) {
                return "The profile file was not found, so this lasts until restart only.";
            }
            String content = Files.readString(profilePath, StandardCharsets.UTF_8);
            int anchor = content.indexOf(ANCHOR);
            if (anchor < 0) {
                return "Could not find `" + ANCHOR + "` in the profile, so this lasts "
                        + "until restart only.";
            }
            int lineEnd = content.indexOf('\n', anchor);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String entry = "\n      # answered in the app on " + LocalDate.now() + "\n"
                    + "      - match: " + quote(match) + "\n"
                    + "        answer: " + quote(answer) + "\n";

            Files.writeString(profilePath.resolveSibling("applicant.yml.bak"), content,
                    StandardCharsets.UTF_8);
            Files.writeString(profilePath,
                    content.substring(0, lineEnd + 1) + entry + content.substring(lineEnd + 1),
                    StandardCharsets.UTF_8);
            return "Written to applicant.yml.";
        } catch (IOException e) {
            log.warn("Could not write the profile: {}", e.getMessage());
            return "Could not write the profile (" + e.getMessage()
                    + "), so this lasts until restart only.";
        }
    }

    /** Double quotes, with any inside escaped. Questions are full of punctuation. */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
