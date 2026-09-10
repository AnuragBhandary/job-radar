package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplyOutcome;
import com.anuragbhandary.jobradar.apply.ApplyService;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import org.springframework.stereotype.Component;

/**
 * {@code apply --posting-id=123} - prepare an application. Nothing is sent.
 *
 * <p>{@code --submit} adds the second step, and even then the application is only
 * sent after the filled form has been described at the terminal and the answer
 * typed is exactly {@code yes}. Not {@code y}: this is the one prompt in the tool
 * where a reflexive keystroke should not be enough.
 *
 * <p>{@code --resume-only} renders the tailored resume and stops: no browser, no
 * board, nothing sent. The way to check what the tailor does to a real posting
 * without spending an application to find out.
 *
 * <p>{@code --all} works through every candidate. It deliberately does not accept
 * {@code --submit} at the same time, which is the single most requested
 * combination and the one that makes this tool a liability rather than an asset.
 * Twenty applications sent unattended from a misread form is not twenty chances
 * taken; it is twenty postings that can never be applied to properly, at
 * companies that keep the record.
 */
@Component
public class ApplyCommand {

    private final PostingRepository postings;
    private final ApplyService applications;

    public ApplyCommand(PostingRepository postings, ApplyService applications) {
        this.postings = postings;
        this.applications = applications;
    }

    public void run(Map<String, String> options) {
        boolean submit = "true".equals(options.get("submit"));
        boolean all = "true".equals(options.get("all"));
        boolean resumeOnly = "true".equals(options.get("resume-only"));

        if (all && submit) {
            System.out.println("""
                    --all and --submit cannot be used together.

                    Batch mode prepares; a human submits. Every board accepts one
                    application per posting, so an unattended run that fills a form
                    wrongly does not cost a rejection - it costs the application you
                    would otherwise have made.

                    Run:  apply --all           then  apply --posting-id=N --submit
                    """);
            return;
        }

        List<Posting> targets = targets(options, all);
        if (targets.isEmpty()) {
            System.out.println("Nothing to apply to. "
                    + "Use --posting-id=N, or --all for every candidate.");
            return;
        }

        for (Posting posting : targets) {
            System.out.printf("%n── %s — %s%n   %s%n",
                    posting.getBoardToken(), posting.getTitle(), posting.getUrl());

            ApplyOutcome outcome = resumeOnly
                    ? applications.renderResumeOnly(posting)
                    : applications.apply(posting, submit, () -> confirm(posting));
            System.out.println("   " + outcome.message().replace("\n", "\n   "));
        }
    }

    private List<Posting> targets(Map<String, String> options, boolean all) {
        String id = options.get("posting-id");
        if (id != null) {
            Optional<Posting> found = postings.findById(Long.valueOf(id));
            if (found.isEmpty()) {
                System.out.println("No posting with id " + id);
                return List.of();
            }
            return List.of(found.get());
        }
        if (!all) {
            return List.of();
        }

        int limit = Integer.parseInt(options.getOrDefault("limit", "5"));
        List<Posting> candidates = new ArrayList<>(
                postings.findRecommended());
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    /**
     * The confirmation. Asked once, at the terminal, after the form is filled and
     * the review file is written - so the person answering has something real to
     * look at rather than an intention to approve.
     */
    private boolean confirm(Posting posting) {
        System.out.printf("""

                   The form is filled and the browser is open.
                   Read the review file and look at the window before answering.

                   Submit this application to %s?
                   Type 'yes' in full to send it, anything else to stop: """,
                posting.getBoardToken());

        try {
            Scanner scanner = new Scanner(System.in);
            return scanner.hasNextLine()
                    && scanner.nextLine().trim().equalsIgnoreCase("yes");
        } catch (RuntimeException e) {
            // No console - a scheduler, or output being piped. Refusing is the
            // only safe reading of "nobody answered".
            System.out.println("(no console - not submitting)");
            return false;
        }
    }
}
