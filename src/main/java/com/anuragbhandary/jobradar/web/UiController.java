package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplyOutcome;
import com.anuragbhandary.jobradar.apply.ApplyService;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The review queue.
 *
 * <p>Everything here is a local, single-user server bound to localhost. There is
 * no authentication because there is no second user and no route in - and if that
 * ever changes, this comment is the thing that has to change with it.
 *
 * <p><b>Submitting is allowed from this UI, and the terminal prompt is not
 * weakened by that.</b> The argument for the terminal gate was never "typing is
 * safer than clicking" - it was that a human should look at the filled form
 * before it is sent. This page shows the full-page screenshot, every derived
 * answer with its reasoning, and the cover letter in full, which is strictly more
 * than the terminal prompt could. The checkbox is the same deliberate act as
 * typing "yes", and the button is disabled until it is ticked.
 *
 * <p>What is still not offered anywhere is a way to submit many at once.
 */
@Controller
public class UiController {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final ApplicationAttemptRepository attempts;
    private final ApplyService applications;

    public UiController(PostingRepository postings, BoardTokenRepository boards,
            ApplicationAttemptRepository attempts, ApplyService applications) {
        this.postings = postings;
        this.boards = boards;
        this.attempts = attempts;
        this.applications = applications;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String queue() {
        List<ApplicationAttempt> recent = attempts.findTop30ByOrderByStartedAtDesc();
        Set<Long> attempted = recent.stream()
                .map(ApplicationAttempt::getPostingId).collect(Collectors.toSet());

        StringBuilder body = new StringBuilder();

        List<ApplicationAttempt> blocked = recent.stream()
                .filter(a -> a.getStatus() == AttemptStatus.NEEDS_HUMAN).toList();
        if (!blocked.isEmpty()) {
            body.append("<h2>Blocked — a question the profile could not answer</h2>");
            blocked.forEach(attempt -> body.append(attemptCard(attempt)));
        }

        List<ApplicationAttempt> prepared = recent.stream()
                .filter(a -> a.getStatus() == AttemptStatus.PREPARED).toList();
        body.append("<h2>Prepared — filled, nothing sent</h2>");
        if (prepared.isEmpty()) {
            body.append("<p class=\"empty\">Nothing waiting.</p>");
        } else {
            prepared.forEach(attempt -> body.append(attemptCard(attempt)));
        }

        body.append("<h2>Candidates not yet prepared</h2>");
        List<Posting> waiting = postings.findByVerdict(Verdict.CANDIDATE).stream()
                .filter(posting -> !attempted.contains(posting.getId()))
                .sorted(Comparator.comparing(Posting::getPostedDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(40)
                .toList();
        if (waiting.isEmpty()) {
            body.append("<p class=\"empty\">None. Run <code>run</code> to fetch and screen.</p>");
        } else {
            waiting.forEach(posting -> body.append(postingCard(posting)));
        }

        List<ApplicationAttempt> sent = recent.stream()
                .filter(a -> a.getStatus() == AttemptStatus.SUBMITTED).toList();
        if (!sent.isEmpty()) {
            body.append("<h2>Submitted</h2>");
            sent.forEach(attempt -> body.append(attemptCard(attempt)));
        }

        return Ui.page("Queue", body.toString());
    }

    /**
     * Prepares one application, synchronously.
     *
     * <p>It launches a browser, reads a form and renders a PDF, so it takes tens
     * of seconds and the request blocks for all of them. Acceptable because there
     * is exactly one user and they are watching a browser window do the work;
     * making it asynchronous would mean a job table and a polling endpoint to
     * report on something already visible on screen.
     */
    @PostMapping("/prepare")
    public String prepare(@RequestParam Long postingId) {
        Optional<Posting> posting = postings.findById(postingId);
        if (posting.isEmpty()) {
            return "redirect:/";
        }
        ApplyOutcome outcome = applications.apply(posting.get(), false, () -> false);
        return "redirect:/attempt/" + outcome.attempt().getId();
    }

    @GetMapping(value = "/attempt/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String attempt(@PathVariable Long id) {
        Optional<ApplicationAttempt> found = attempts.findById(id);
        if (found.isEmpty()) {
            return Ui.page("Not found", "<p class=\"empty\">No attempt " + id + ".</p>");
        }
        ApplicationAttempt attempt = found.get();

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"card\"><div class=\"row\"><span class=\"title\">")
                .append(Ui.esc(attempt.getCompany())).append(" — ")
                .append(Ui.esc(attempt.getRole())).append("</span>")
                .append(status(attempt)).append("</div><div class=\"meta\">")
                .append(Ui.esc(attempt.getTailoringNote())).append("</div></div>");

        if (attempt.getBlockerReason() != null) {
            body.append("<div class=\"note\"><strong>Blocked:</strong> ")
                    .append(Ui.esc(attempt.getBlockerReason()))
                    .append("<br>Add an answer under <code>extra-answers</code> in "
                            + "applicant.yml, or run <code>learn --write</code>.</div>");
        }

        if (attempt.getResumePath() != null) {
            body.append("<h2>Resume</h2><p><a href=\"/resume/").append(id)
                    .append("\" target=\"_blank\">Open the tailored PDF</a></p>");
        }

        if (attempt.getCoverLetter() != null && !attempt.getCoverLetter().isBlank()) {
            body.append("<h2>Cover letter</h2>").append(Ui.pre(attempt.getCoverLetter()));
        }

        if (attempt.getFieldLog() != null) {
            body.append("<h2>Every field</h2>").append(Ui.pre(attempt.getFieldLog()));
        }

        if (attempt.getScreenshotPath() != null) {
            body.append("<h2>The filled form</h2><img class=\"shot\" src=\"/shot/")
                    .append(id).append("\" alt=\"the filled application form\">");
        }

        if (attempt.getStatus() == AttemptStatus.PREPARED) {
            body.append(submitForm(attempt));
        }

        return Ui.page(attempt.getCompany(), body.toString());
    }

    /**
     * Re-fills the form and submits it.
     *
     * <p>The prepared browser closed when the prepare run finished, so this fills
     * again from scratch rather than reusing a page. That is slower and is also
     * the only correct thing to do: the posting may have been edited or taken down
     * since, and submitting a form filled twenty minutes ago against a page that
     * has changed is how the wrong answer reaches an employer.
     */
    @PostMapping("/submit")
    public String submit(@RequestParam Long attemptId, @RequestParam(required = false) String read) {
        Optional<ApplicationAttempt> found = attempts.findById(attemptId);
        if (found.isEmpty() || !"on".equals(read)) {
            return "redirect:/attempt/" + attemptId;
        }
        Optional<Posting> posting = postings.findById(found.get().getPostingId());
        if (posting.isEmpty()) {
            return "redirect:/attempt/" + attemptId;
        }
        ApplyOutcome outcome = applications.apply(posting.get(), true, () -> true);
        return "redirect:/attempt/" + outcome.attempt().getId();
    }

    @GetMapping("/shot/{id}")
    public ResponseEntity<byte[]> screenshot(@PathVariable Long id) {
        return file(attempts.findById(id).map(ApplicationAttempt::getScreenshotPath),
                MediaType.IMAGE_PNG);
    }

    @GetMapping("/resume/{id}")
    public ResponseEntity<byte[]> resume(@PathVariable Long id) {
        return file(attempts.findById(id).map(ApplicationAttempt::getResumePath),
                MediaType.APPLICATION_PDF);
    }

    /**
     * Serves a file this application wrote, and only those.
     *
     * <p>The path comes from the database rather than from the request, so there
     * is no traversal to defend against - but the existence check stays, because
     * an attempt that failed before rendering has a null path and a 404 is a
     * better answer than a stack trace.
     */
    private ResponseEntity<byte[]> file(Optional<String> path, MediaType type) {
        if (path.isEmpty() || path.get() == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = Path.of(path.get());
        if (!Files.isReadable(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok()
                    .contentType(type)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private String submitForm(ApplicationAttempt attempt) {
        return """
                <h2>Send it</h2>
                <div class="note">This re-opens the form and fills it again before
                submitting, because the posting may have changed since it was
                prepared. Most boards accept one application per posting, ever.</div>
                <form method="post" action="/submit">
                  <input type="hidden" name="attemptId" value="%d">
                  <label class="check">
                    <input type="checkbox" name="read" onchange="go.disabled=!checked">
                    <span>I have read the filled form above, including the derived
                    answers and the cover letter.</span>
                  </label>
                  <button id="go" class="primary" disabled>Submit to %s</button>
                </form>
                """.formatted(attempt.getId(), Ui.esc(attempt.getCompany()));
    }

    private String postingCard(Posting posting) {
        String company = boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());

        return """
                <div class="card"><div class="row">
                  <span><span class="title">%s</span> — %s</span>
                  <form method="post" action="/prepare">
                    <input type="hidden" name="postingId" value="%d">
                    <button>Prepare</button>
                  </form>
                </div>
                <div class="meta">%s · %s · <a href="%s" target="_blank">posting</a>%s</div>
                </div>
                """.formatted(
                        Ui.esc(company), Ui.esc(posting.getTitle()), posting.getId(),
                        Ui.esc(String.valueOf(posting.getCountry())),
                        Ui.esc(posting.getLocation()),
                        Ui.esc(posting.getUrl()),
                        posting.getSalaryText() == null ? ""
                                : " · " + Ui.esc(posting.getSalaryText()));
    }

    private String attemptCard(ApplicationAttempt attempt) {
        return """
                <div class="card"><div class="row">
                  <span><span class="title">%s</span> — %s</span>
                  %s
                </div>
                <div class="meta"><a href="/attempt/%d">review</a> · %s%s</div>
                </div>
                """.formatted(
                        Ui.esc(attempt.getCompany()), Ui.esc(attempt.getRole()),
                        status(attempt), attempt.getId(),
                        WHEN.format(attempt.getStartedAt()),
                        attempt.getBlockerReason() == null ? ""
                                : " · " + Ui.esc(attempt.getBlockerReason()));
    }

    private static String status(ApplicationAttempt attempt) {
        String kind = switch (attempt.getStatus()) {
            case SUBMITTED -> "ok";
            case NEEDS_HUMAN, FAILED -> "bad";
            case PREPARED -> "warn";
            case SKIPPED -> "";
        };
        return "<span class=\"tag " + kind + "\">" + attempt.getStatus() + "</span>";
    }
}
