package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationField;
import com.anuragbhandary.jobradar.apply.FieldActions;
import com.anuragbhandary.jobradar.apply.PreparationRunner;
import com.anuragbhandary.jobradar.apply.Readiness;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds what the preparation screen reads, for both of its clients.
 *
 * <p>The JSON endpoints and the server-rendered page show the same thing, so they
 * are given the same objects to show. Two builders would drift, and the one that
 * drifts is always the one nobody is looking at.
 *
 * <p>Conflicts are computed for a whole attempt at a time and then handed out per
 * field. Asking each field on its own turns one page render into forty resolver
 * passes over the same context, and the answer would be identical every time.
 */
@Component
public class PreparationViews {

    private final FieldActions actions;
    private final PostingRepository postings;
    private final PreparationRunner runner;

    public PreparationViews(FieldActions actions, PostingRepository postings,
            PreparationRunner runner) {
        this.actions = actions;
        this.postings = postings;
        this.runner = runner;
    }

    public PreparationView view(ApplicationAttempt attempt) {
        Posting posting = postings.findById(attempt.getPostingId()).orElse(null);
        return PreparationView.of(attempt, posting, readiness(attempt.getId()),
                runner.isRunning(attempt.getId()));
    }

    public Readiness readiness(Long attemptId) {
        return Readiness.of(actions.forAttempt(attemptId),
                actions.conflictedFields(attemptId).size());
    }

    public List<FieldView> fields(Long attemptId) {
        Set<Long> conflicted = actions.conflictedFields(attemptId);
        return actions.forAttempt(attemptId).stream()
                .map(field -> FieldView.of(field, conflicted.contains(field.getId())))
                .toList();
    }

    /** One field, after an action changed it. */
    public FieldView view(ApplicationField field) {
        return FieldView.of(field,
                actions.conflictedFields(field.getAttemptId()).contains(field.getId()));
    }
}
