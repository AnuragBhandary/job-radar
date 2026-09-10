package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ActionRefused;
import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplicationField;
import com.anuragbhandary.jobradar.apply.FieldActions;
import com.anuragbhandary.jobradar.apply.FieldExplanation;
import com.anuragbhandary.jobradar.apply.PreparationRunner;
import com.anuragbhandary.jobradar.apply.ScopeOption;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The preparation screen's data, as JSON.
 *
 * <p>Small on purpose. Every rule lives in {@link FieldActions}; these methods
 * find a row, call it, and turn the answer into a response - which is what makes
 * the plain-form fallback in {@link PreparationController} a genuine second client
 * rather than a second implementation.
 *
 * <p>A local, single-user server bound to localhost, like the rest of the web
 * layer. There is no authentication because there is no second user and no route
 * in; if that ever changes, this comment is the thing that has to change with it.
 */
@RestController
public class PreparationApi {

    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final PreparationViews views;
    private final FieldActions actions;
    private final PreparationRunner runner;

    public PreparationApi(ApplicationAttemptRepository attempts, PostingRepository postings,
            PreparationViews views, FieldActions actions, PreparationRunner runner) {
        this.attempts = attempts;
        this.postings = postings;
        this.views = views;
        this.actions = actions;
        this.runner = runner;
    }

    /**
     * What is happening, and what is left.
     *
     * <p>The endpoint the page polls. It reads two tables and computes counts;
     * it starts nothing and changes nothing, so polling it every second while a
     * browser works costs a query.
     */
    @GetMapping("/api/preparation/{id}")
    public ResponseEntity<PreparationView> progress(@PathVariable Long id) {
        Optional<ApplicationAttempt> attempt = attempts.findById(id);
        if (attempt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(views.view(attempt.get()));
    }

    @GetMapping("/api/preparation/{id}/fields")
    public ResponseEntity<List<FieldView>> fields(@PathVariable Long id) {
        if (attempts.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(views.fields(id));
    }

    @GetMapping("/api/field/{id}/explain")
    public FieldExplanation explain(@PathVariable Long id) {
        return actions.explain(id);
    }

    /** The scopes this answer may be saved at. Decided here, never in the page. */
    @GetMapping("/api/field/{id}/scopes")
    public List<ScopeOption> scopes(@PathVariable Long id) {
        return actions.scopesFor(id);
    }

    @PostMapping("/api/field/{id}/override")
    public FieldView override(@PathVariable Long id, @RequestParam long version,
            @RequestParam String value) {
        return one(actions.override(id, version, value));
    }

    @PostMapping("/api/field/{id}/answer")
    public FieldView answer(@PathVariable Long id, @RequestParam long version,
            @RequestParam String value,
            @RequestParam(required = false) String scopeLevel,
            @RequestParam(required = false) String scopeValue) {
        return one(actions.answer(id, version, value, scopeLevel, scopeValue));
    }

    @PostMapping("/api/field/{id}/approve")
    public FieldView approve(@PathVariable Long id, @RequestParam long version,
            @RequestParam(required = false) Long assertionId,
            @RequestParam(required = false) String value) {
        return one(actions.approve(id, version, assertionId, value));
    }

    @PostMapping("/api/field/{id}/reject")
    public FieldView reject(@PathVariable Long id, @RequestParam long version,
            @RequestParam(required = false) Long assertionId) {
        return one(actions.reject(id, version, assertionId));
    }

    @PostMapping("/api/field/{id}/regenerate")
    public FieldView regenerate(@PathVariable Long id, @RequestParam long version,
            @RequestParam(required = false) Long assertionId) {
        return one(actions.regenerate(id, version, assertionId));
    }

    /** Starts a preparation and answers immediately with the attempt to watch. */
    @PostMapping("/api/preparation")
    public ResponseEntity<PreparationView> start(@RequestParam Long postingId) {
        Optional<Posting> posting = postings.findById(postingId);
        if (posting.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ApplicationAttempt attempt = runner.start(posting.get());
        return ResponseEntity.accepted().body(views.view(attempt));
    }

    // ------------------------------------------------------------------

    private FieldView one(ApplicationField field) {
        return views.view(field);
    }

    /**
     * A refusal, as a response a client can act on.
     *
     * <p>409 rather than 500: nothing broke. The request described a world that
     * has moved on, or asked for something the knowledge rules forbid, and both
     * are answers rather than errors. The body carries what happened and what to
     * do about it, and never a stack trace.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(ActionRefused.class)
    public ResponseEntity<Refusal> refused(ActionRefused refusal) {
        HttpStatus status = switch (refusal.code()) {
            case "not-found" -> HttpStatus.NOT_FOUND;
            case "invalid", "unsafe" -> HttpStatus.BAD_REQUEST;
            case "unavailable" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(new Refusal(refusal.code(), refusal.what(), refusal.remedy()));
    }

    /** @param remedy what the applicant can do next. Never omitted. */
    public record Refusal(String code, String what, String remedy) {
    }
}
