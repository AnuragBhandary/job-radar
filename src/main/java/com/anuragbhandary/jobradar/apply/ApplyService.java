package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.form.BrowserSession;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FillReport;
import com.anuragbhandary.jobradar.apply.form.FormField;
import com.anuragbhandary.jobradar.apply.form.FormFiller;
import com.anuragbhandary.jobradar.apply.form.FormReader;
import com.anuragbhandary.jobradar.apply.form.PdfWriter;
import com.anuragbhandary.jobradar.apply.form.Submitter;
import com.anuragbhandary.jobradar.apply.letter.CoverLetterWriter;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.plan.ResumePipeline;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.sheets.ApplicationRow;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prepares one application end to end, and stops.
 *
 * <p>The shape of this class is the argument of the whole feature. Everything up
 * to the submit button is automated, because it is mechanical and takes twenty
 * minutes a posting by hand. The submit button is not, because it is the one step
 * that cannot be undone and the one where being wrong is expensive: most boards
 * accept a single application per posting forever, so a form filled from a
 * misread label does not cost a rejection, it costs the good application that
 * could have been made instead.
 *
 * <p>So the default run ends with a filled browser window, a screenshot, and a
 * review file. Submitting is a second, explicit act.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    /** Month, not day: a recruiter seeing "2026-09-07" knows exactly when it was generated. */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * The resume for a posting: selected from the evidence bank, or tailored as
     * before when there is no bank, it does not match the resume, or it is
     * switched off. Either way, selection only - nothing here writes a sentence.
     */
    private final ResumePipeline resumes;
    private final ResumeRenderer renderer;
    private final ResumeModel resume;
    private final PdfWriter pdf;
    private final CoverLetterWriter letters;
    private final FormReader reader;
    private final FormFiller filler;
    private final Submitter submitter;
    private final SheetsClient sheets;
    private final BoardTokenRepository boards;
    private final ApplicationAttemptRepository attempts;
    private final ApplyProperties config;
    /**
     * Runs the new knowledge resolver beside the mapper and writes the diff.
     * Read-only: it cannot change an answer, and nothing here reads its output.
     */
    private final com.anuragbhandary.jobradar.knowledge.ShadowComparator shadow;
    /** Writes the structured record of what happened to each field. */
    private final FieldRecorder recorder;
    /** Attaches drafts to the open-ended questions, for him to approve or reject. */
    private final ProposalService proposals;
    private final com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory contexts;
    /** "anurag-bhandary" - taken from the profile so the file name is never hardcoded. */
    private final String applicantSlug;

    public ApplyService(
            ResumePipeline resumes, ResumeRenderer renderer, ResumeModel resume, PdfWriter pdf,
            CoverLetterWriter letters, FormReader reader, FormFiller filler, Submitter submitter,
            SheetsClient sheets, BoardTokenRepository boards,
            ApplicationAttemptRepository attempts, ApplyProperties config,
            ApplicantProfile profile,
            com.anuragbhandary.jobradar.knowledge.ShadowComparator shadow,
            FieldRecorder recorder, ProposalService proposals,
            com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory contexts) {
        this.resumes = resumes;
        this.renderer = renderer;
        this.resume = resume;
        this.pdf = pdf;
        this.letters = letters;
        this.reader = reader;
        this.filler = filler;
        this.submitter = submitter;
        this.sheets = sheets;
        this.boards = boards;
        this.attempts = attempts;
        this.config = config;
        this.shadow = shadow;
        this.recorder = recorder;
        this.proposals = proposals;
        this.contexts = contexts;
        // Capitalised, unlike the directory slugs: this one is a file name a
        // recruiter sees in their downloads folder.
        this.applicantSlug = (profile.name().first() + "-" + profile.name().last())
                .replaceAll("[^A-Za-z0-9-]", "");
    }

    /**
     * @param wantsSubmit    whether the caller intends to submit at all
     * @param confirmSubmit  asked only once everything is filled and the review
     *                       file is on disk, so the human answering it is deciding
     *                       about a real form rather than an intention. Supplying
     *                       a supplier that always returns true is possible and is
     *                       the documented way to get this wrong.
     */
    public ApplyOutcome apply(Posting posting, boolean wantsSubmit, BooleanSupplier confirmSubmit) {
        return apply(posting, new PreparationRequest(null, PreparedAnswers.none(), false,
                wantsSubmit, confirmSubmit, StageReporter.NONE));
    }

    /**
     * Creates the row an asynchronous preparation will report into.
     *
     * <p>Split out so the web layer can answer a request in milliseconds with an
     * attempt id and then do the browser work behind it. Everything here is a
     * database read and a database write; nothing launches, renders or navigates.
     *
     * @return an attempt already saved. {@link AttemptStatus#SKIPPED} when this
     *         posting has been applied to, in which case there is nothing to run.
     */
    @org.springframework.transaction.annotation.Transactional
    public ApplicationAttempt beginAttempt(Posting posting) {
        String company = companyName(posting);
        Optional<ApplicationAttempt> already = attempts.findFirstByPostingIdAndStatus(
                posting.getId(), AttemptStatus.SUBMITTED);
        ApplicationAttempt attempt =
                new ApplicationAttempt(posting.getId(), company, posting.getTitle());
        if (already.isPresent()) {
            attempt.setStatus(AttemptStatus.SKIPPED);
            attempt.setBlockerReason("already submitted on "
                    + already.get().getFinishedAt() + " (attempt "
                    + already.get().getId() + ")");
            attempt.setStage(PreparationStage.FINISHED, "nothing to do");
            attempt.setFinishedAt(Instant.now());
            return attempts.save(attempt);
        }
        attempt.setStatus(AttemptStatus.PREPARING);
        attempt.setStage(PreparationStage.QUEUED, PreparationStage.QUEUED.detail());
        return attempts.save(attempt);
    }

    /** Puts a resumable attempt back into the queue, keeping what it already did. */
    @org.springframework.transaction.annotation.Transactional
    public ApplicationAttempt requeue(ApplicationAttempt attempt) {
        attempt.setStatus(AttemptStatus.PREPARING);
        attempt.setManualReason(null);
        attempt.setBlockerReason(null);
        attempt.setFinishedAt(null);
        attempt.setStage(PreparationStage.QUEUED, PreparationStage.QUEUED.detail());
        return attempts.save(attempt);
    }

    /**
     * Prepares one application, reporting where it has got to as it goes.
     *
     * <p>Resuming is the interesting path. Preparation stops whenever it needs a
     * person - an unanswerable required question, a draft to approve, a control
     * the page would not take - and picking it up again must not throw away the
     * work that already succeeded. So a resumed run reuses the rendered resume and
     * the drafted letter where they are still on disk, and re-reads and re-fills
     * the form, which is the one part that genuinely cannot be reused: the browser
     * closed, and the posting may have changed since.
     */
    public ApplyOutcome apply(Posting posting, PreparationRequest request) {
        String company = companyName(posting);
        StageReporter reporter = request.reporter();

        ApplicationAttempt resumed = request.preserveWork()
                ? attempts.findById(request.resumeAttemptId()).orElse(null) : null;
        ApplicationAttempt attempt = resumed != null ? resumed
                : new ApplicationAttempt(posting.getId(), company, posting.getTitle());

        Optional<ApplicationAttempt> already = attempts.findFirstByPostingIdAndStatus(
                posting.getId(), AttemptStatus.SUBMITTED);
        if (already.isPresent() && !already.get().getId().equals(attempt.getId())) {
            attempt.setStatus(AttemptStatus.SKIPPED);
            attempt.setBlockerReason("already submitted on "
                    + already.get().getFinishedAt() + " (attempt "
                    + already.get().getId() + ")");
            attempt.setStage(PreparationStage.FINISHED, "nothing to do");
            attempt.setFinishedAt(Instant.now());
            return new ApplyOutcome(attempts.save(attempt), null, null,
                    "Already applied to this posting. Nothing done.");
        }

        // Saved before the work starts, so the structured field rows have an
        // attempt to belong to and so a run that dies mid-browser leaves a record
        // saying it was preparing rather than leaving nothing at all.
        attempt.setStatus(AttemptStatus.PREPARING);
        ApplicationAttempt persisted = attempts.save(attempt);

        Path workDir = workDirFor(company, posting);
        try {
            // 1. Documents. Rendered before the browser opens, so a resume that
            //    fails to render costs nothing and leaves no half-filled form.
            //    On a resumed run the PDF on disk is the one that was going to be
            //    sent, so re-rendering it would change the application for no
            //    reason and lose the note explaining what was tailored.
            stage(attempt, reporter, PreparationStage.TAILORING, null);
            Path resumePdf = reusableResume(attempt);
            if (resumePdf == null) {
                TailoringPlan plan = resumes.tailor(posting);
                TailoredResume tailored = plan.resume();
                // Absolute, not "./applications/...". Playwright's setInputFiles rejects
                // a relative path with "Cannot get absolute file path", and the failure
                // arrives on whatever field the file input's label resolved to - which
                // on Ashby is "Name", so it reads as the name field being broken.
                resumePdf = workDir.resolve(resumeFileName(company))
                        .toAbsolutePath().normalize();
                pdf.write(renderer.toHtml(tailored, resume.headline()), resumePdf);
                attempt.setResumePath(resumePdf.toString());
                attempt.setTailoringNote(plan.note());
                attempt.setSummaryId(tailored.summary().id());
                log.info("Resume: {}", plan.note());
            } else {
                log.info("Reusing the resume already rendered for attempt {}", attempt.getId());
            }

            stage(attempt, reporter, PreparationStage.OPENING, posting.getBoardToken());
            try (BrowserSession session = BrowserSession.persistent(
                    Path.of(expand(config.browserProfile())), config.headless())) {

                Page page = session.newPage();
                page.setDefaultTimeout(config.pageTimeoutSeconds() * 1000.0);
                page.navigate(posting.getUrl());
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                stage(attempt, reporter, PreparationStage.READING, null);
                List<FormField> fields = readFormRevealingItIfNeeded(page, posting);
                if (fields.isEmpty()) {
                    attempt.setManualReason(ManualReason.NO_FORM);
                    return stop(attempt, AttemptStatus.MANUAL_REQUIRED, workDir, null,
                            "No form found at " + posting.getUrl()
                                    + " - the board may require a sign-in, or the apply "
                                    + "link may lead somewhere else. The browser has closed; "
                                    + "your tailored resume is saved and this one is a "
                                    + "five-minute job by hand.");
                }

                stage(attempt, reporter, PreparationStage.RESOLVING,
                        fields.size() + " fields read");

                // 2. The letter, only if there is a box for it. See CoverLetterWriter.
                //    Reused on a resumed run: a model does not write the same text
                //    twice, and the letter he read on the review screen is the one
                //    that has to go in the box.
                Optional<FormField> letterBox = fields.stream()
                        .filter(f -> f.kind() == FieldKind.COVER_LETTER_TEXT)
                        .findFirst();
                String letter = attempt.getCoverLetter() != null
                                && !attempt.getCoverLetter().isBlank()
                        ? attempt.getCoverLetter()
                        : letterBox
                                // Tailored again only here, and only when a form
                                // reveals a letter box that the earlier run did
                                // not have one for. The writer needs the resume
                                // model, not the note on the row.
                                .flatMap(box -> letters.write(posting, company,
                                        resumes.tailor(posting).resume(), 0))
                                .orElse(null);
                if (letterBox.isEmpty() && letter == null) {
                    log.info("No cover letter text box on this form - none written");
                }
                attempt.setCoverLetter(letter);

                ApplicationDocuments documents = new ApplicationDocuments(
                        resumePdf, letter, attempt.getTailoringNote());

                // 3. Fill. Nothing in this call can submit.
                stage(attempt, reporter, PreparationStage.FILLING, null);
                FillReport report = filler.fill(page, fields, posting, documents,
                        request.settled(), contexts.of(posting, documents));
                attempt.setFieldLog(renderFieldLog(report));
                attempt.setOpenQuestions(
                        OpenQuestion.serialise(OpenQuestion.from(report)));

                stage(attempt, reporter, PreparationStage.CAPTURED, null);
                Path screenshot = workDir.resolve("filled-form.png");
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(screenshot).setFullPage(true));
                attempt.setScreenshotPath(screenshot.toString());

                Path review = writeReviewFile(
                        workDir, posting, company, attempt.getTailoringNote(), letter, report);
                writeShadowReport(workDir, fields, posting, documents);

                // The structured record: one queryable row per field, with what
                // was known and what the browser managed kept apart.
                AttemptStatus fieldStatus = recordFields(persisted, posting, report, company);
                // Drafts for the questions nothing could answer. After the
                // screenshot and the review file, so a model that is slow or
                // unavailable cannot cost the record of a form that is already
                // filled.
                fieldStatus = draftAnswers(persisted, posting, documents, fieldStatus);

                // 3a. Handed back to a person, with the form filled and on screen.
                //     Not a failure and not a submission: the browser stays until
                //     the window is closed, which is the only way a board that
                //     wants a human gets one.
                if (request.leaveOpen()) {
                    attempt.setStatus(fieldStatus);
                    attempt.setStage(PreparationStage.FINISHED,
                            "open in the browser for you to finish");
                    attempt.setFinishedAt(Instant.now());
                    attempts.save(attempt);
                    session.waitForManualWork(page, config.pageTimeoutSeconds() * 1000L * 20);
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Opened and filled " + report.filledCount()
                                    + " field(s). Finish and submit it yourself - "
                                    + "nothing here will.");
                }

                if (!report.isSubmittable()) {
                    String reason = report.blockers().stream()
                            .map(b -> b.field().label())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("a field failed to fill");
                    attempt.setBlockerReason(reason);
                    attempt.setStatus(fieldStatus);
                    if (fieldStatus == AttemptStatus.MANUAL_REQUIRED) {
                        attempt.setManualReason(ManualReason.UNSUPPORTED_CONTROL);
                    }
                    attempt.setStage(PreparationStage.FINISHED, null);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Filled " + report.filledCount() + " field(s), but cannot submit: "
                                    + reason + "\nThe browser has closed. Answer it on the "
                                    + "preparation screen and every future form asking the "
                                    + "same thing can use it.");
                }

                if (!request.wantsSubmit()) {
                    attempt.setStatus(fieldStatus);
                    attempt.setStage(PreparationStage.FINISHED, null);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Prepared and filled " + report.filledCount()
                                    + " field(s). Nothing submitted.\nReview: " + review);
                }

                // 4. The only place an application is sent.
                if (!request.confirmSubmit().getAsBoolean()) {
                    attempt.setStatus(fieldStatus);
                    attempt.setStage(PreparationStage.FINISHED, null);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Not submitted. The form stays filled in the open browser.");
                }

                String landedOn = submitter.submit(page, report, true);
                attempt.setStatus(AttemptStatus.SUBMITTED);
                attempt.setStage(PreparationStage.FINISHED, "sent");
                attempt.setFinishedAt(Instant.now());

                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(workDir.resolve("after-submit.png")).setFullPage(true));

                recordInTracker(attempt, posting, company);
                return new ApplyOutcome(attempts.save(attempt), report, review,
                        "Submitted. Landed on " + landedOn);
            }

        } catch (Submitter.CaptchaPresentException e) {
            // The board is asking for a person, and gets one. Distinct from a
            // failure: the form is filled and every answer on it is still good.
            attempt.setManualReason(ManualReason.CAPTCHA);
            return stop(attempt, AttemptStatus.MANUAL_REQUIRED, workDir,
                    e.getMessage(), e.getMessage());
        } catch (Exception e) {
            log.error("Application failed", e);
            attempt.setStage(PreparationStage.FAILED, firstLine(e.getMessage()));
            return stop(attempt, AttemptStatus.FAILED, workDir, e.getMessage(),
                    "Failed: " + e.getMessage());
        }
    }

    /**
     * Moves the stage, on the row and on the caller's reporter.
     *
     * <p>Both are wrapped: a progress bar that throws must not be able to lose an
     * application that is otherwise going fine. The stage is bookkeeping and the
     * browser work is not.
     */
    private void stage(ApplicationAttempt attempt, StageReporter reporter,
            PreparationStage stage, String detail) {
        String line = detail == null ? stage.detail() : detail;
        try {
            attempt.setStage(stage, line);
            attempts.save(attempt);
        } catch (RuntimeException e) {
            log.debug("Could not record stage {}: {}", stage, e.getMessage());
        }
        try {
            reporter.at(stage, line);
        } catch (RuntimeException e) {
            log.debug("Stage reporter threw at {}: {}", stage, e.getMessage());
        }
    }

    /**
     * The resume this attempt already rendered, when it is still there.
     *
     * <p>Null when there is none or the file has been moved, in which case a fresh
     * one is rendered - the path on the row is a record of what was sent, and a
     * record pointing at nothing must not become a form submitted without a
     * resume.
     */
    private static Path reusableResume(ApplicationAttempt attempt) {
        String path = attempt.getResumePath();
        if (path == null || path.isBlank()) {
            return null;
        }
        Path pdf = Path.of(path);
        return Files.exists(pdf) ? pdf : null;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int cut = message.indexOf('\n');
        String line = cut < 0 ? message : message.substring(0, cut);
        return line.length() > 200 ? line.substring(0, 197) + "..." : line;
    }

    /**
     * Renders the tailored resume for a posting and stops - no browser, no board.
     *
     * <p>The way to see what the tailor does to a real posting without spending an
     * application on finding out, and the way to check a change to the resume YAML
     * against the corpus rather than against one's expectations. Also the only
     * part of this feature that works with no network at all.
     */
    public ApplyOutcome renderResumeOnly(Posting posting) {
        String company = companyName(posting);
        ApplicationAttempt attempt =
                new ApplicationAttempt(posting.getId(), company, posting.getTitle());
        Path workDir = workDirFor(company, posting);

        TailoringPlan plan = resumes.tailor(posting);
        TailoredResume tailored = plan.resume();
        Path resumePdf = workDir.resolve(resumeFileName(company));
        pdf.write(renderer.toHtml(tailored, resume.headline()), resumePdf);

        attempt.setStatus(AttemptStatus.SKIPPED);
        attempt.setResumePath(resumePdf.toString());
        attempt.setTailoringNote(plan.note());
        attempt.setSummaryId(tailored.summary().id());
        attempt.setBlockerReason("resume-only run - no form was opened");
        attempt.setStage(PreparationStage.FINISHED, "resume rendered, no form opened");
        attempt.setFinishedAt(Instant.now());

        return new ApplyOutcome(attempts.save(attempt), null, resumePdf,
                plan.note() + "\n" + resumePdf);
    }

    /**
     * Some boards put the form behind an "Apply" button on the description page.
     *
     * <p>Reading zero fields is therefore not proof there is no form. One click on
     * something that clearly means "show me the form" is worth trying; anything
     * beyond that is guessing at a page's structure, and the tool stops and says
     * so instead.
     */
    private List<FormField> readFormRevealingItIfNeeded(Page page, Posting posting) {
        List<FormField> fields = reader.read(page);
        if (!fields.isEmpty()) {
            return fields;
        }

        // Try the known address before trying to find a button. Ashby and Lever
        // both put the form on their own URL, and Ashby exposes the link with
        // role=tab rather than as a button or a link named "Apply" - so no amount
        // of looking for an Apply control finds it. Navigating is also cheaper and
        // more repeatable than clicking something that might be a scroll anchor.
        String applyUrl = applicationUrlFor(posting);
        if (applyUrl != null) {
            log.info("No form on the page - trying {}", applyUrl);
            page.navigate(applyUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            fields = reader.read(page);
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        for (String name : List.of(
                "Apply for this job", "Apply for this position", "Apply now",
                "Apply to this job", "Submit application", "Apply")) {

            for (AriaRole role : List.of(AriaRole.BUTTON, AriaRole.LINK)) {
                Locator control = page.getByRole(role,
                        new Page.GetByRoleOptions().setName(name).setExact(false));
                if (control.count() == 0 || !control.first().isVisible()) {
                    continue;
                }
                log.info("No form on the page - clicking '{}'", name);
                control.first().click();
                page.waitForLoadState(LoadState.NETWORKIDLE);
                List<FormField> revealed = reader.read(page);
                if (!revealed.isEmpty()) {
                    return revealed;
                }
                // The click may have scrolled to an anchor rather than navigated.
                // One more attempt is the limit; past that this is guessing at a
                // page's structure, and it stops and says so instead.
            }
        }
        return List.of();
    }

    /**
     * The URL of the application form, where the platform puts it somewhere else.
     *
     * <p>Only for the two where the pattern is documented and stable. Guessing a
     * URL on the others would mean navigating away from a page that does have the
     * form, which turns a working application into a 404.
     *
     * @return the form's address, or null when the posting URL is already it
     */
    static String applicationUrlFor(Posting posting) {
        String url = posting.getUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.replaceAll("[?#].*$", "").replaceAll("/+$", "");
        return switch (posting.getSource()) {
            // https://jobs.ashbyhq.com/{token}/{id} -> .../application
            case ASHBY -> trimmed.endsWith("/application") ? null : trimmed + "/application";
            // https://jobs.lever.co/{token}/{id} -> .../apply
            case LEVER -> trimmed.endsWith("/apply") ? null : trimmed + "/apply";
            // Greenhouse renders the form inline; Recruitee's careers_apply_url
            // already lands on it; SmartRecruiters, Amazon and Workday all differ
            // per tenant and are left to the click fallback.
            case GREENHOUSE, SMARTRECRUITERS, AMAZON, WORKDAY, RECRUITEE -> null;
        };
    }

    /**
     * Writes the row to the Google Sheet.
     *
     * <p>A failure here is logged and swallowed. The application has already been
     * sent; throwing now would report the whole attempt as failed, which is the
     * single most misleading thing this method could do.
     */
    private void recordInTracker(ApplicationAttempt attempt, Posting posting, String company) {
        if (!sheets.isConfigured()) {
            log.info("No spreadsheet configured - not recording in the tracker");
            return;
        }
        try {
            ApplicationRow row = ApplicationRow.from(posting, company, LocalDate.now())
                    .withNotes("auto-applied by job-radar; attempt " + attempt.getId());
            attempt.setTrackerRow(sheets.appendApplication(row));
        } catch (IOException | RuntimeException e) {
            log.warn("Submitted, but could not write the tracker row: {}. "
                    + "Add it by hand - sheet-append --posting-id={}",
                    e.getMessage(), posting.getId());
        }
    }

    /**
     * Records a stopped attempt.
     *
     * <p>The blocker defaults to the message rather than to null. It was null on
     * the "no form found" path, and the result was an attempt row that said
     * NEEDS_HUMAN with an empty reason - which is the one thing a blocked attempt
     * must never be, because the reason is the entire output. It looked fine in
     * the terminal, where the message is printed, and was empty everywhere the
     * row is read afterwards.
     */
    private ApplyOutcome stop(
            ApplicationAttempt attempt, AttemptStatus status,
            Path workDir, String blocker, String message) {
        attempt.setStatus(status);
        attempt.setBlockerReason(blocker != null ? blocker : message);
        if (attempt.getStage() == null || attempt.getStage().isRunning()) {
            attempt.setStage(status == AttemptStatus.FAILED
                    ? PreparationStage.FAILED : PreparationStage.FINISHED, null);
        }
        attempt.setFinishedAt(Instant.now());
        return new ApplyOutcome(attempts.save(attempt), null, workDir, message);
    }

    /**
     * The review file: everything a human needs to decide, in one place.
     *
     * <p>Markdown, and on disk, for the same reason the digest is: it survives the
     * terminal scrolling, it can be read on a phone, and it is still there
     * tomorrow when the reply arrives and the question is what was actually sent.
     */
    private Path writeReviewFile(
            Path workDir, Posting posting, String company, String tailoringNote,
            String letter, FillReport report) throws IOException {

        StringBuilder out = new StringBuilder();
        out.append("# ").append(company).append(" — ").append(posting.getTitle()).append("\n\n")
                .append("- Posting: ").append(posting.getUrl()).append('\n')
                .append("- Location: ").append(nullSafe(posting.getLocation()))
                .append("  (").append(posting.getCountry()).append(")\n")
                .append("- Stated pay: ").append(orNone(posting.getSalaryText())).append('\n')
                .append("- Sponsorship language: ")
                .append(orNone(posting.getSponsorshipSignal())).append('\n')
                .append("- Resume tailoring: ").append(nullSafe(tailoringNote))
                .append("\n\n");

        // The derived answers first. They are the ones that are computed rather
        // than copied, so they are the ones worth a human's attention.
        List<FillReport.Entry> review = report.needingReview();
        if (!review.isEmpty()) {
            out.append("## Check these\n\n");
            for (FillReport.Entry entry : review) {
                out.append("- **").append(entry.field().label()).append("** → `")
                        .append(abbreviate(entry.answer().value())).append("`  \n  ")
                        .append(nullSafe(entry.answer().note())).append('\n');
            }
            out.append('\n');
        }

        if (!report.blockers().isEmpty()) {
            out.append("## Blocked\n\n");
            for (FillReport.Entry entry : report.blockers()) {
                out.append("- ").append(entry.field().label()).append(" — ")
                        .append(entry.error() == null
                                ? entry.answer().note() : entry.error()).append('\n');
            }
            out.append('\n');
        }

        if (letter != null) {
            out.append("## Cover letter\n\n").append(letter).append("\n\n");
        }

        out.append("## Every field\n\n```\n");
        report.entries().forEach(entry -> out.append(entry.describe()).append('\n'));
        out.append("```\n");

        Path review1 = workDir.resolve("review.md");
        Files.writeString(review1, out.toString(), StandardCharsets.UTF_8);
        return review1;
    }

    /**
     * Records the structured field rows, and says what they add up to.
     *
     * <p>Swallowed on failure, and the old status is kept if it throws. This is
     * bookkeeping running after a real application has already been filled and
     * screenshotted; it must not be the thing that loses the run.
     */
    private AttemptStatus recordFields(ApplicationAttempt attempt, Posting posting,
            FillReport report, String company) {
        try {
            return recorder.record(attempt.getId(), posting, contexts.of(posting),
                    report, company);
        } catch (RuntimeException e) {
            log.warn("Could not record structured fields for attempt {}: {}",
                    attempt.getId(), e.getMessage());
            return report.isSubmittable()
                    ? AttemptStatus.READY_FOR_REVIEW : AttemptStatus.AWAITING_ANSWER;
        }
    }

    /**
     * Asks for drafts, and says what the fields add up to afterwards.
     *
     * <p>Swallowed on failure, like the field record it follows. A model being
     * unavailable is an ordinary condition here - there is no key on most days -
     * and it must leave a prepared application prepared.
     */
    private AttemptStatus draftAnswers(ApplicationAttempt attempt, Posting posting,
            ApplicationDocuments documents, AttemptStatus fallback) {
        try {
            return proposals.proposeFor(attempt.getId(),
                    contexts.of(posting, documents), fallback);
        } catch (RuntimeException e) {
            log.debug("No drafts for attempt {}: {}", attempt.getId(), e.getMessage());
            return fallback;
        }
    }

    /**
     * Writes the shadow diff beside the review file.
     *
     * <p>Swallows everything it can throw. This is an experiment running next to
     * a real application, and an experiment that can fail the thing it is
     * observing is worse than no experiment - the form is already filled and the
     * review file is already on disk by the time this runs.
     */
    private void writeShadowReport(Path workDir, List<FormField> fields, Posting posting,
            ApplicationDocuments documents) {
        try {
            Files.writeString(workDir.resolve("shadow.md"),
                    shadow.render(shadow.compare(fields, posting, documents)),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Shadow comparison skipped: {}", e.getMessage());
        }
    }

    private static String renderFieldLog(FillReport report) {
        StringBuilder log1 = new StringBuilder();
        report.entries().forEach(entry -> log1.append(entry.describe()).append('\n'));
        return log1.toString();
    }

    private Path workDirFor(String company, Posting posting) {
        String slug = slug(company) + "-" + slug(posting.getTitle());
        Path dir = Path.of(expand(config.outputDir()))
                .resolve(LocalDate.now() + "-" + slug + "-" + posting.getId());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + dir, e);
        }
        return dir;
    }

    /**
     * The file name a recruiter sees in their downloads folder.
     *
     * <p>Worth getting right: "resume.pdf" is what everyone else's attachment is
     * called, and a name with the applicant's in it survives being saved.
     */
    private String resumeFileName(String company) {
        return applicantSlug + "-" + slug(company) + "-"
                + LocalDate.now().format(MONTH) + ".pdf";
    }

    private String companyName(Posting posting) {
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    /** {@code ~} is not expanded by the JVM, and every default path here uses it. */
    static String expand(String path) {
        return path != null && path.startsWith("~")
                ? System.getProperty("user.home") + path.substring(1)
                : path;
    }

    /** Lowercase, hyphen-separated, and short enough to keep a path readable. */
    static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "x";
        }
        String cleaned = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (cleaned.isEmpty()) {
            return "x";
        }
        // Trim on a hyphen rather than mid-word, so a truncated directory name is
        // still readable.
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
            int lastDash = cleaned.lastIndexOf('-');
            if (lastDash > 10) {
                cleaned = cleaned.substring(0, lastDash);
            }
        }
        return cleaned;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "_not stated_" : value;
    }
}
