package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplyOutcome;
import com.anuragbhandary.jobradar.apply.ApplyService;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.apply.OpenQuestion;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>A local, single-user server bound to localhost. There is no authentication
 * because there is no second user and no route in - and if that ever changes,
 * this comment is the thing that has to change with it.
 *
 * <p><b>Submitting is allowed from here, and the terminal prompt is not weakened
 * by that.</b> The argument for the terminal gate was never that typing is safer
 * than clicking; it was that a human should look at the filled form before it is
 * sent. This page shows the full-page screenshot, every derived answer with its
 * reasoning, and the cover letter in full, which is strictly more than a terminal
 * prompt can. The checkbox is the same deliberate act as typing "yes".
 *
 * <p>What is still not offered anywhere is a way to submit many at once.
 */
@Controller
public class UiController {

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final ApplicationAttemptRepository attempts;
    private final ApplyService applications;
    private final AssistantService assistant;
    private final MatchScorer scorer;
    private final JobInterestRepository interests;

    public UiController(PostingRepository postings, BoardTokenRepository boards,
            ApplicationAttemptRepository attempts, ApplyService applications,
            AssistantService assistant, MatchScorer scorer, JobInterestRepository interests) {
        this.postings = postings;
        this.boards = boards;
        this.attempts = attempts;
        this.applications = applications;
        this.assistant = assistant;
        this.scorer = scorer;
        this.interests = interests;
    }

    // ------------------------------------------------------------------
    // The queue
    // ------------------------------------------------------------------

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String queue(@RequestParam(required = false) String country) {
        List<ApplicationAttempt> recent = attempts.findTop30ByOrderByStartedAtDesc();
        Set<Long> attempted = recent.stream()
                .map(ApplicationAttempt::getPostingId).collect(Collectors.toSet());

        List<Posting> candidates = postings.findByVerdict(Verdict.CANDIDATE);
        Set<Long> tracked = interests.findAll().stream()
                .map(com.anuragbhandary.jobradar.pipeline.JobInterest::getPostingId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // Scored, then ranked. Sorting by date was close to random: a posting is
        // not more relevant for being newer, and "newest first" over 65 candidates
        // meant reading them in the order the boards happened to publish them.
        List<Scored> waiting = candidates.stream()
                .filter(posting -> !attempted.contains(posting.getId()))
                .filter(posting -> !tracked.contains(posting.getId()))
                .filter(posting -> country == null
                        || country.equalsIgnoreCase(String.valueOf(posting.getCountry())))
                .map(posting -> new Scored(posting, scorer.score(posting)))
                .sorted(Comparator.comparingInt((Scored s) -> s.score().score()).reversed()
                        .thenComparing(scored -> scored.posting().getPostedDate(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        StringBuilder body = new StringBuilder();
        body.append(filters(candidates, country));

        section(body, "Blocked — a question the profile could not answer",
                recent.stream().filter(a -> a.getStatus() == AttemptStatus.NEEDS_HUMAN).toList(),
                "Nothing blocked.");
        section(body, "Prepared — filled, nothing sent",
                recent.stream().filter(a -> a.getStatus() == AttemptStatus.PREPARED).toList(),
                "Nothing waiting.");

        body.append("<section class=\"section\">")
                .append(Ui.sectionHead("Candidates not yet prepared",
                        waiting.size() + (waiting.size() > 40 ? " · showing 40" : "")))
                .append("<div class=\"card list\">");
        if (waiting.isEmpty()) {
            body.append("<p class=\"empty\">None. Run <code>run</code> to fetch and screen.</p>");
        } else {
            waiting.stream().limit(40).forEach(scored -> body.append(postingRow(scored)));
        }
        body.append("</div></section>");
        // Preparing blocks for the better part of a minute while a browser opens,
        // reads the form and renders a PDF. Without this the button looks broken
        // and gets clicked again, which starts a second browser.
        body.append("""
                <script>
                document.querySelectorAll('.prepare-form').forEach(function (f) {
                  f.addEventListener('submit', function () {
                    var b = f.querySelector('button');
                    b.disabled = true;
                    b.textContent = 'preparing…';
                    f.insertAdjacentHTML('afterend',
                      '<span class="note-muted">opens a browser, about a minute</span>');
                  });
                });
                </script>
                """);

        section(body, "Submitted",
                recent.stream().filter(a -> a.getStatus() == AttemptStatus.SUBMITTED).toList(),
                "Nothing submitted yet. Applications appear here only after you press "
                        + "submit on a review page.");

        long screened = postings.count();
        String stat = "<strong>%s</strong> postings screened<span class=\"sep\">·</span>"
                .formatted(String.format("%,d", screened))
                + "<strong>%d</strong> candidates<span class=\"sep\">·</span>"
                        .formatted(candidates.size())
                + "<strong>%d</strong> attempts".formatted(attempts.count());

        return Ui.page("Queue", stat, body.toString());
    }

    /** One filter link per country that actually has candidates. */
    private String filters(List<Posting> candidates, String active) {
        Map<Country, Long> byCountry = candidates.stream()
                .filter(posting -> posting.getCountry() != null)
                .collect(Collectors.groupingBy(Posting::getCountry,
                        LinkedHashMap::new, Collectors.counting()));

        StringBuilder out = new StringBuilder(
                "<nav class=\"filters\" aria-label=\"Filter by country\">");
        out.append(filterLink("All", candidates.size(), null, active));
        byCountry.entrySet().stream()
                .sorted(Map.Entry.<Country, Long>comparingByValue().reversed())
                .forEach(entry -> out.append(filterLink(
                        title(entry.getKey().name()), entry.getValue().intValue(),
                        entry.getKey().name(), active)));
        return out.append("</nav>").toString();
    }

    private static String filterLink(String label, int count, String value, String active) {
        boolean on = value == null ? active == null : value.equalsIgnoreCase(active);
        return "<a href=\"%s\" class=\"%s\">%s <span class=\"n\">%d</span></a>".formatted(
                value == null ? "/" : "/?country=" + value,
                on ? "is-active" : "", Ui.esc(label), count);
    }

    private void section(StringBuilder body, String title,
            List<ApplicationAttempt> rows, String emptyText) {
        body.append("<section class=\"section\">")
                .append(Ui.sectionHead(title, String.valueOf(rows.size())))
                .append("<div class=\"card list\">");
        if (rows.isEmpty()) {
            body.append("<p class=\"empty\">").append(Ui.esc(emptyText)).append("</p>");
        } else {
            rows.forEach(attempt -> body.append(attemptRow(attempt)));
        }
        body.append("</div></section>");
    }

    /** A posting with its score, so the feed can rank before it renders. */
    private record Scored(Posting posting, com.anuragbhandary.jobradar.match.MatchScore score) {
    }

    private String postingRow(Scored scored) {
        Posting posting = scored.posting();
        String company = companyOf(posting);

        StringBuilder meta = new StringBuilder("<div class=\"meta\">");
        if (posting.getCountry() != null) {
            meta.append(Ui.badge("country", posting.getCountry().name()));
        }
        if (posting.getLocation() != null && !posting.getLocation().isBlank()) {
            meta.append("<span>").append(Ui.esc(shortLocation(posting.getLocation())))
                    .append("</span>");
        }
        String age = age(posting.getPostedDate());
        if (age != null) {
            meta.append("<span class=\"dot\">·</span><span>").append(age).append("</span>");
        }
        // Pay and sponsorship are tags rather than columns: fewer than a fifth of
        // postings state either, and an empty column on four rows in five reads
        // as a broken table.
        if (posting.getSalaryText() != null && !posting.getSalaryText().isBlank()) {
            meta.append("<span class=\"tag\">")
                    .append(Ui.esc(truncate(posting.getSalaryText(), 44))).append("</span>");
        }
        if (posting.getSponsorshipSignal() != null && !posting.getSponsorshipSignal().isBlank()) {
            meta.append("<span class=\"tag\">")
                    .append(Ui.esc(truncate(posting.getSponsorshipSignal(), 44))).append("</span>");
        }
        meta.append("</div>");

        return """
                <article class="row">
                  %s
                  <div class="row-main">
                    <div class="row-title">
                      <span class="company">%s</span>
                      <span class="role">%s</span>
                    </div>
                    %s
                    <div class="why">%s</div>
                  </div>
                  <div class="row-side">
                    <a href="%s" target="_blank" rel="noreferrer">posting</a>
                    <form method="post" action="/save">
                      <input type="hidden" name="postingId" value="%d">
                      <button class="btn btn-sm" type="submit">Save</button>
                    </form>
                    <form method="post" action="/prepare" class="prepare-form">
                      <input type="hidden" name="postingId" value="%d">
                      <button class="btn" type="submit">Prepare</button>
                    </form>
                  </div>
                </article>
                """.formatted(Ui.scoreCell(scored.score()),
                        Ui.esc(company), Ui.esc(posting.getTitle()), meta,
                        Ui.esc(scored.score().headline()),
                        Ui.esc(posting.getUrl()), posting.getId(), posting.getId());
    }

    private String attemptRow(ApplicationAttempt attempt) {
        // Truncated. The tailoring note is a full sentence naming the summary, the
        // matched tags and the dropped projects; unabridged it wraps to three
        // lines and every row in the list becomes the height of a paragraph.
        // It is shown in full on the review page, where there is room for it.
        String detail = attempt.getBlockerReason() != null
                ? "<p class=\"reason\">" + Ui.esc(truncate(attempt.getBlockerReason(), 110))
                        + "</p>"
                : "<div class=\"meta\"><span>"
                        + Ui.esc(truncate(nullSafe(attempt.getTailoringNote()), 110))
                        + "</span></div>";

        return """
                <article class="row">
                  <div class="row-main">
                    <div class="row-title">
                      <span class="company">%s</span>
                      <span class="role">%s</span>
                      %s
                    </div>
                    %s
                    <div class="meta"><span>%s</span></div>
                  </div>
                  <div class="row-side"><a href="/attempt/%d">review</a></div>
                </article>
                """.formatted(Ui.esc(attempt.getCompany()), Ui.esc(attempt.getRole()),
                        statusBadge(attempt), detail, ago(attempt.getStartedAt()),
                        attempt.getId());
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

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

    /**
     * Re-fills the form and submits it.
     *
     * <p>The prepared browser closed when the prepare run finished, so this fills
     * again from scratch rather than reusing a page. That is slower and is also
     * the only correct thing to do: the posting may have been edited or taken
     * down since, and submitting a form filled twenty minutes ago against a page
     * that has changed is how the wrong answer reaches an employer.
     */
    @PostMapping("/submit")
    public String submit(@RequestParam Long attemptId,
            @RequestParam(required = false) String read) {
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

    // ------------------------------------------------------------------
    // The review page
    // ------------------------------------------------------------------

    @GetMapping(value = "/attempt/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String attempt(@PathVariable Long id) {
        Optional<ApplicationAttempt> found = attempts.findById(id);
        if (found.isEmpty()) {
            return Ui.page("Not found", "",
                    "<p class=\"empty\">No attempt " + id + ".</p>");
        }
        ApplicationAttempt attempt = found.get();
        Optional<Posting> posting = postings.findById(attempt.getPostingId());

        StringBuilder body = new StringBuilder();
        body.append("<p class=\"crumbs\"><a href=\"/\">queue</a> · attempt ")
                .append(id).append("</p>");

        body.append(head(attempt, posting.orElse(null)));

        List<FieldRow> rows = FieldRow.parse(attempt.getFieldLog());
        derived(body, rows);
        blocked(body, attempt);
        letter(body, attempt);
        fieldTable(body, rows);
        screenshot(body, attempt);

        body.append(Ui.assistant(attempt.getId(), assistant.isUsable(), openQuestions(attempt)));

        if (attempt.getStatus() == AttemptStatus.PREPARED) {
            body.append(submitBlock(attempt));
        }

        String stat = "attempt <strong>" + id + "</strong><span class=\"sep\">·</span>"
                + (attempt.getStatus() == AttemptStatus.SUBMITTED ? "sent" : "nothing sent yet");
        return Ui.page(attempt.getCompany(), stat, body.toString());
    }

    private String head(ApplicationAttempt attempt, Posting posting) {
        StringBuilder meta = new StringBuilder("<div class=\"meta\">");
        if (posting != null) {
            if (posting.getCountry() != null) {
                meta.append(Ui.badge("country", posting.getCountry().name()));
            }
            meta.append("<span>").append(Ui.esc(nullSafe(posting.getLocation()))).append("</span>");
            String age = age(posting.getPostedDate());
            if (age != null) {
                meta.append("<span class=\"dot\">·</span><span>posted ").append(age)
                        .append("</span>");
            }
            meta.append("<span class=\"dot\">·</span><span>stated pay: ")
                    .append(Ui.esc(orNone(posting.getSalaryText()))).append("</span>")
                    .append("<span class=\"dot\">·</span><span>sponsorship language: ")
                    .append(Ui.esc(orNone(posting.getSponsorshipSignal()))).append("</span>");
        }
        meta.append("</div>");

        String note = attempt.getTailoringNote() == null ? ""
                : "<p class=\"resume-note\">Resume: " + Ui.esc(attempt.getTailoringNote())
                        + "</p>";

        return """
                <section class="card attempt-head">
                  <div class="head-top"><h1>%s</h1>%s</div>
                  <span class="role">%s</span>
                  %s%s
                </section>
                """.formatted(Ui.esc(attempt.getCompany()), statusBadge(attempt),
                        Ui.esc(attempt.getRole()), meta, note);
    }

    /**
     * The answers the tool worked out rather than copied.
     *
     * <p>First on the page, and the only section given an accent border. These are
     * the ones a human can actually catch: a name copied from the profile is right
     * by construction, and a sponsorship answer derived from the posting's country
     * is right only if the derivation is.
     */
    private void derived(StringBuilder body, List<FieldRow> rows) {
        List<FieldRow> reviewable = rows.stream()
                .filter(row -> row.origin() != null)
                .toList();
        if (reviewable.isEmpty()) {
            return;
        }
        body.append("<section class=\"card panel check\">")
                .append(Ui.panelHead("Check these",
                        Ui.noteMuted("worked out by the tool, not copied from the profile")));
        for (FieldRow row : reviewable) {
            body.append("""
                    <div class="check-item">
                      <p class="check-q">%s</p>
                      <p class="check-a">%s</p>
                      <p class="check-why">%s</p>
                    </div>
                    """.formatted(Ui.esc(row.label()), Ui.esc(row.displayValue()),
                            Ui.esc(row.why() == null ? row.origin() : row.why())));
        }
        body.append("</section>");
    }

    private void blocked(StringBuilder body, ApplicationAttempt attempt) {
        if (attempt.getBlockerReason() == null || attempt.getBlockerReason().isBlank()) {
            return;
        }
        String[] items = attempt.getBlockerReason().split(";\\s*");
        StringBuilder list = new StringBuilder("<ul class=\"blocked-list\">");
        for (String item : items) {
            list.append("<li><span class=\"q\">").append(Ui.esc(item.trim()))
                    .append("</span></li>");
        }
        list.append("</ul>");

        body.append("<section class=\"card panel callout-warn\">")
                .append(Ui.panelHead("Blocked", Ui.badge("warn", String.valueOf(items.length))))
                .append(list)
                .append("<p class=\"empty\">Add an answer under <code>extra-answers</code> in "
                        + "applicant.yml, or draft one below and save it.</p>")
                .append("</section>");
    }

    private void letter(StringBuilder body, ApplicationAttempt attempt) {
        if (attempt.getCoverLetter() == null || attempt.getCoverLetter().isBlank()) {
            return;
        }
        StringBuilder paragraphs = new StringBuilder();
        for (String paragraph : attempt.getCoverLetter().trim().split("\n\\s*\n")) {
            paragraphs.append("<p>").append(Ui.esc(paragraph.trim())).append("</p>");
        }
        body.append("<section class=\"card panel\">")
                .append(Ui.panelHead("Cover letter", Ui.badge("soft", "generated")))
                .append("<div class=\"panel-body letter\">").append(paragraphs)
                .append("</div></section>");
    }

    private void fieldTable(StringBuilder body, List<FieldRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        long filled = rows.stream().filter(r -> "filled".equals(r.status())).count();
        long failed = rows.stream().filter(r -> "FAILED".equals(r.status())).count();
        String summary = "%d fields · %d filled · %d skipped · %d failed".formatted(
                rows.size(), filled, rows.size() - filled - failed, failed);

        StringBuilder table = new StringBuilder("""
                <div class="table-wrap"><table class="fields"><thead><tr>
                <th class="col-status">Status</th><th class="col-label">Field</th><th>Value</th>
                </tr></thead><tbody>
                """);
        for (FieldRow row : rows) {
            String origin = row.origin() == null ? ""
                    : Ui.badge("origin", row.origin().split(";")[0].trim());
            table.append("""
                    <tr>
                      <td><span class="st %s">%s</span></td>
                      <td><span class="cell-label"><span class="truncate">%s</span>%s</span></td>
                      <td><span class="truncate %s">%s</span></td>
                    </tr>
                    """.formatted(row.statusClass(), Ui.esc(row.status()),
                            Ui.esc(row.label()), origin,
                            row.valueIsReason() ? "val-muted" : "val",
                            Ui.esc(row.displayValue())));
        }
        table.append("</tbody></table></div>");

        body.append("<section class=\"card panel\">")
                .append(Ui.panelHead("Every field", Ui.noteMuted(summary)))
                .append(table).append("</section>");
    }

    /**
     * The screenshot, boxed.
     *
     * <p>It is 1440 by 4536 pixels. Left to itself it is three thousand pixels of
     * page and everything below it is unreachable, which is how it behaved before
     * this frame existed.
     */
    private void screenshot(StringBuilder body, ApplicationAttempt attempt) {
        if (attempt.getScreenshotPath() == null) {
            return;
        }
        body.append("<section class=\"card panel\">")
                .append(Ui.panelHead("The filled form",
                        "<button class=\"btn btn-sm\" type=\"button\" id=\"shot-toggle\" "
                                + "aria-expanded=\"false\">expand</button>"))
                .append("""
                        <div class="panel-body">
                          <div class="shot-frame" id="shot-frame" tabindex="0"
                               aria-label="Screenshot of the filled form, scrollable">
                            <img src="/shot/%d" alt="the filled application form">
                          </div>
                          <p class="caption">captured before submit · scroll inside the frame</p>
                        </div></section>
                        <script>
                        (function () {
                          var t = document.getElementById('shot-toggle');
                          var f = document.getElementById('shot-frame');
                          t.onclick = function () {
                            var open = f.classList.toggle('is-expanded');
                            t.setAttribute('aria-expanded', String(open));
                            t.textContent = open ? 'collapse' : 'expand';
                          };
                        })();
                        </script>
                        """.formatted(attempt.getId()));
    }

    private String submitBlock(ApplicationAttempt attempt) {
        return """
                <section class="submit-block">
                  <h2>Submit</h2>
                  <p class="submit-note">This re-opens the form and fills it again before
                  submitting, because the posting may have changed since it was prepared.
                  Most boards accept one application per posting, ever.</p>
                  <form method="post" action="/submit">
                    <input type="hidden" name="attemptId" value="%d">
                    <label class="confirm">
                      <input type="checkbox" name="read" onchange="go.disabled=!checked">
                      <span>I have read the filled form above, including the derived
                      answers and the cover letter.</span>
                    </label>
                    <button id="go" class="btn btn-primary" disabled>Submit to %s</button>
                  </form>
                </section>
                """.formatted(attempt.getId(), Ui.esc(attempt.getCompany()));
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

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
            return ResponseEntity.ok().contentType(type)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ------------------------------------------------------------------
    // Small things
    // ------------------------------------------------------------------

    /**
     * The questions this form asked and the profile could not answer.
     *
     * <p>Offered to the assistant as one-click chips, because they are the
     * specific thing a human sitting on this page is stuck on. A free-text box
     * alone would mean retyping a question already on screen.
     */
    private static List<String> openQuestions(ApplicationAttempt attempt) {
        return OpenQuestion.parse(attempt.getOpenQuestions()).stream()
                .map(OpenQuestion::label)
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private String companyOf(Posting posting) {
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    private static String statusBadge(ApplicationAttempt attempt) {
        String kind = switch (attempt.getStatus()) {
            case SUBMITTED -> "ok";
            case NEEDS_HUMAN, FAILED -> "bad";
            case PREPARED -> "warn";
            case SKIPPED -> "country";
        };
        return Ui.badge(kind, attempt.getStatus().name());
    }

    /** "3 days ago", or null when the board published no date. */
    private static String age(LocalDate posted) {
        if (posted == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(posted, LocalDate.now());
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "yesterday" : days + " days ago";
    }

    private static String ago(Instant when) {
        Duration since = Duration.between(when, Instant.now());
        if (since.toHours() < 1) {
            return Math.max(1, since.toMinutes()) + " min ago";
        }
        if (since.toDays() < 1) {
            return since.toHours() + (since.toHours() == 1 ? " hour ago" : " hours ago");
        }
        return since.toDays() == 1 ? "yesterday" : since.toDays() + " days ago";
    }

    /**
     * Locations arrive as "Utrecht, Utrecht, Netherlands; Utrecht, Netherlands".
     * The first clause is enough on a list row.
     */
    private static String shortLocation(String location) {
        return truncate(location.split(";")[0].trim(), 42);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static String title(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }
}
