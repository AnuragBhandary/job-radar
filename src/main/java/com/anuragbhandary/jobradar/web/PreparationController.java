package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ActionRefused;
import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplyOutcome;
import com.anuragbhandary.jobradar.apply.ApplyService;
import com.anuragbhandary.jobradar.apply.FieldActions;
import com.anuragbhandary.jobradar.apply.PreparationRunner;
import com.anuragbhandary.jobradar.apply.ScopeOption;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Application Preparation workflow, as pages.
 *
 * <p>Everything here posts and redirects. That is not a fallback for a missing
 * JavaScript layer - it is the layer, and {@code prep.js} adds polling and the
 * explanation panels on top of a screen that already works with scripts off.
 *
 * <h2>Where the safety lives</h2>
 * Not here. Every rule about what may be approved, answered or scoped is in
 * {@link FieldActions}, and this class turns an {@link ActionRefused} into a
 * sentence on the page. The one thing it does own is that no route in this file
 * submits an application except {@code /submit}, which goes through
 * {@link ApplyService} to {@code Submitter} exactly as it always has.
 */
@Controller
public class PreparationController {

    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final ApplyService applications;
    private final PreparationRunner runner;
    private final FieldActions actions;
    private final PreparationViews views;

    public PreparationController(ApplicationAttemptRepository attempts,
            PostingRepository postings, ApplyService applications, PreparationRunner runner,
            FieldActions actions, PreparationViews views) {
        this.attempts = attempts;
        this.postings = postings;
        this.applications = applications;
        this.runner = runner;
        this.actions = actions;
        this.views = views;
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /**
     * Starts a preparation and answers straight away.
     *
     * <p>It used to hold the connection for the forty seconds of PDF rendering,
     * browser launching, form reading and filling that follow. Now it creates the
     * attempt, hands the work to {@link PreparationRunner} and redirects to a page
     * that shows the work happening.
     */
    @PostMapping("/prepare")
    public String prepare(@RequestParam Long postingId, RedirectAttributes flash) {
        Optional<Posting> posting = postings.findById(postingId);
        if (posting.isEmpty()) {
            return "redirect:/jobs";
        }
        ApplicationAttempt attempt = runner.start(posting.get());
        if (attempt.getStatus() == com.anuragbhandary.jobradar.apply.AttemptStatus.SKIPPED) {
            flash.addAttribute("said", attempt.getBlockerReason());
        }
        return "redirect:/attempt/" + attempt.getId();
    }

    /** Picks an attempt up where it stopped, keeping the work it already did. */
    @PostMapping("/attempt/{id}/resume")
    public String resume(@PathVariable Long id, RedirectAttributes flash) {
        Optional<ApplicationAttempt> attempt = runner.resume(id);
        flash.addAttribute("said", attempt.isEmpty() ? "No attempt " + id + "."
                : "Picking up where it stopped. The resume, the letter and everything "
                        + "you have settled are kept.");
        return "redirect:/attempt/" + id;
    }

    /**
     * Re-fills the form and leaves it on screen.
     *
     * <p>The end of the workflow, and deliberately not the end of the application.
     * Nothing on this path can submit: it fills, it screenshots, and it waits for
     * the window to be closed. The board's own button stays the applicant's.
     */
    @PostMapping("/attempt/{id}/open")
    public String open(@PathVariable Long id, RedirectAttributes flash) {
        Optional<ApplicationAttempt> found = attempts.findById(id);
        if (found.isEmpty()) {
            return "redirect:/jobs";
        }
        if (!views.readiness(id).canOpen(found.get().getManualReason())) {
            // The same rule the button obeys, enforced where it counts. A client
            // that posts this anyway gets the reason rather than a re-filled form
            // with the same blank in it.
            flash.addAttribute("said",
                    "Not opened: there are still required questions with no answer. "
                            + "Answer them first and the form goes in complete.");
            return "redirect:/attempt/" + id;
        }
        runner.open(id);
        flash.addAttribute("said", "Opening the form in a browser with everything "
                + "prepared. It will not be submitted - that is yours.");
        return "redirect:/attempt/" + id;
    }

    /**
     * Re-fills the form and submits it.
     *
     * <p>Unchanged from before this phase, on purpose. The prepared browser closed
     * when the run finished, so this fills again from scratch rather than reusing
     * a page: the posting may have been edited or taken down since, and submitting
     * a form filled twenty minutes ago against a page that has changed is how the
     * wrong answer reaches an employer.
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
    // The screen
    // ------------------------------------------------------------------

    @GetMapping(value = "/attempt/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String attempt(@PathVariable Long id,
            @RequestParam(required = false) String said) {
        Optional<ApplicationAttempt> found = attempts.findById(id);
        if (found.isEmpty()) {
            return Ui.page("Not found", "",
                    "<p class=\"empty\">No attempt " + id + ".</p>");
        }
        ApplicationAttempt attempt = found.get();
        List<FieldView> fields = views.fields(id);

        // Only for the questions that have one, so a form with forty fields does
        // not resolve forty scope pickers nobody will look at.
        Map<Long, List<ScopeOption>> scopes = new HashMap<>();
        fields.stream()
                .filter(field -> field.actions().contains("answer"))
                .forEach(field -> scopes.put(field.id(), actions.scopesFor(field.id())));

        return PreparationPage.render(attempt, views.view(attempt), fields, scopes, said);
    }

    /**
     * One decision about one field.
     *
     * <p>A single endpoint with the action named in the form, rather than six
     * near-identical ones. The dispatch is three lines and the validation is none
     * of this class's business - {@link FieldActions} refuses what has to be
     * refused, and the refusal comes back as two sentences: what happened, and
     * what to do about it.
     */
    @PostMapping("/attempt/{id}/act")
    public String act(@PathVariable Long id, @RequestParam Long fieldId,
            @RequestParam long version, @RequestParam String action,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) Long assertionId,
            @RequestParam(required = false) String scopeLevel,
            @RequestParam(required = false) String scopeValue,
            RedirectAttributes flash) {

        try {
            switch (action) {
                case "answer" -> {
                    actions.answer(fieldId, version, value, scopeLevel, scopeValue);
                    flash.addAttribute("said", scopeLevel == null || scopeLevel.isBlank()
                            ? "Answered, for this application only."
                            : "Answered, and saved for " + readable(scopeLevel) + ".");
                }
                case "override" -> {
                    actions.override(fieldId, version, value);
                    flash.addAttribute("said",
                            "Changed. The original answer is kept in the record.");
                }
                case "approve" -> {
                    actions.approve(fieldId, version, assertionId, value);
                    flash.addAttribute("said", value == null || value.isBlank()
                            ? "Approved. It stays marked as drafted by the assistant."
                            : "Edited and approved. It stays marked as drafted "
                                    + "by the assistant.");
                }
                case "reject" -> {
                    actions.reject(fieldId, version, assertionId);
                    flash.addAttribute("said",
                            "Rejected. The draft is kept in the record and will not be used.");
                }
                case "regenerate" -> {
                    actions.regenerate(fieldId, version, assertionId);
                    flash.addAttribute("said",
                            "Drafted again. The previous version is kept in the record.");
                }
                default -> flash.addAttribute("said", "'" + action + "' is not something "
                        + "that can be done to a field.");
            }
        } catch (ActionRefused refusal) {
            flash.addAttribute("said", refusal.what() + " " + refusal.remedy());
        }
        return "redirect:/attempt/" + id;
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
        if (!Files.exists(file)) {
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

    private static String readable(String level) {
        return level.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
