package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant's HTTP surface. JSON in, JSON out, called by the review page.
 *
 * <p>Everything it returns is a <em>draft</em>. Nothing here writes to a form, and
 * the one thing that writes anywhere is {@code /assistant/save-answer}, which
 * appends to {@code applicant.yml} after the applicant has read the text and
 * pressed a button. That separation is the same one the rest of the tool keeps:
 * the machine prepares, the person decides.
 */
@RestController
public class AssistantController {

    private final AssistantService assistant;
    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final BoardTokenRepository boards;

    public AssistantController(AssistantService assistant,
            ApplicationAttemptRepository attempts, PostingRepository postings,
            BoardTokenRepository boards) {
        this.assistant = assistant;
        this.attempts = attempts;
        this.postings = postings;
        this.boards = boards;
    }

    public record Ask(Long attemptId, String mode, String text) {
    }

    public record Reply(boolean ok, String text, String note) {
    }

    @PostMapping(value = "/assistant", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Reply assist(@RequestBody Ask ask) {
        if (!assistant.isUsable()) {
            return new Reply(false, null,
                    "No model configured. Set job-radar.llm in ~/.config/job-radar/secrets.yml.");
        }

        Optional<ApplicationAttempt> attempt = attempts.findById(ask.attemptId());
        if (attempt.isEmpty()) {
            return new Reply(false, null, "No attempt " + ask.attemptId() + ".");
        }
        Optional<Posting> posting = postings.findById(attempt.get().getPostingId());
        if (posting.isEmpty()) {
            return new Reply(false, null, "The posting behind this attempt is gone.");
        }

        String company = attempt.get().getCompany();
        AssistantService.Draft draft = switch (nullSafe(ask.mode())) {
            case "answer" -> assistant.answerQuestion(posting.get(), company, ask.text());
            case "letter" -> assistant.rewriteLetter(
                    posting.get(), company, attempt.get().getCoverLetter(), ask.text());
            case "ask" -> assistant.ask(posting.get(), company, ask.text());
            default -> AssistantService.Draft.none();
        };

        if (!draft.ok()) {
            return new Reply(false, null,
                    "Nothing usable came back. For an application question that "
                            + "usually means your material does not support an answer, "
                            + "which is the honest result.");
        }

        // A rewritten letter is stored, because it is what would be sent and the
        // attempt is the record of that. A drafted answer is not: it belongs in
        // the profile, and only once he has read it.
        if ("letter".equals(ask.mode())) {
            attempt.get().setCoverLetter(draft.text());
            attempts.save(attempt.get());
        }
        return new Reply(true, draft.text(), draft.rejected());
    }

    /**
     * Appends a question and its answer to {@code applicant.yml}.
     *
     * <p>Closes the loop that {@code learn} opens: the question that blocked this
     * form becomes an entry that unblocks the next one. It appends at the top of
     * the block, because first match wins and a new specific entry has to sit
     * above an older general one, and it keeps a {@code .bak} - that file is
     * hand-maintained and holds everything the tool knows about him.
     */
    @PostMapping(value = "/assistant/save-answer", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Reply saveAnswer(@RequestBody Map<String, String> body) {
        String match = body.get("match");
        String answer = body.get("answer");
        if (match == null || match.isBlank() || answer == null || answer.isBlank()) {
            return new Reply(false, null, "Both the question and the answer are needed.");
        }

        Path profile = Path.of(System.getProperty("user.home"),
                ".config", "job-radar", "applicant.yml");
        try {
            String content = Files.readString(profile, StandardCharsets.UTF_8);
            int anchor = content.indexOf("extra-answers:");
            if (anchor < 0) {
                return new Reply(false, null,
                        "Could not find extra-answers: in " + profile);
            }
            int lineEnd = content.indexOf('\n', anchor);

            String entry = "\n      # added from the review page on " + LocalDate.now()
                    + "\n      - match: " + yaml(shorten(match))
                    + "\n        answer: " + yaml(answer) + "\n";

            Files.writeString(profile.resolveSibling("applicant.yml.bak"), content,
                    StandardCharsets.UTF_8);
            Files.writeString(profile,
                    content.substring(0, lineEnd + 1) + entry + content.substring(lineEnd + 1),
                    StandardCharsets.UTF_8);

            return new Reply(true, null,
                    "Saved. It takes effect on the next run, and the previous "
                            + "applicant.yml is beside it as .bak.");
        } catch (IOException e) {
            return new Reply(false, null, "Could not write the profile: " + e.getMessage());
        }
    }

    /**
     * The first six words, lowercased.
     *
     * <p>Matching is substring containment, so the whole question is the worst
     * possible key: "Are you at least 18 years of age?" and "...18 years old?" are
     * the same question and neither matches the other.
     */
    private static String shorten(String question) {
        String[] words = com.anuragbhandary.jobradar.apply.form.FieldClassifier
                .normalise(question).split(" ");
        return String.join(" ", java.util.Arrays.copyOfRange(
                words, 0, Math.min(words.length, 6)));
    }

    /** Double-quoted with the two characters that would break the string escaped. */
    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
