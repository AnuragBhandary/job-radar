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
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
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

    private final ResumeTailor tailor;
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
    /** "anurag-bhandary" - taken from the profile so the file name is never hardcoded. */
    private final String applicantSlug;

    public ApplyService(
            ResumeTailor tailor, ResumeRenderer renderer, ResumeModel resume, PdfWriter pdf,
            CoverLetterWriter letters, FormReader reader, FormFiller filler, Submitter submitter,
            SheetsClient sheets, BoardTokenRepository boards,
            ApplicationAttemptRepository attempts, ApplyProperties config,
            ApplicantProfile profile) {
        this.tailor = tailor;
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
        String company = companyName(posting);
        ApplicationAttempt attempt =
                new ApplicationAttempt(posting.getId(), company, posting.getTitle());

        Optional<ApplicationAttempt> already = attempts.findFirstByPostingIdAndStatus(
                posting.getId(), AttemptStatus.SUBMITTED);
        if (already.isPresent()) {
            attempt.setStatus(AttemptStatus.SKIPPED);
            attempt.setBlockerReason("already submitted on "
                    + already.get().getFinishedAt() + " (attempt "
                    + already.get().getId() + ")");
            attempt.setFinishedAt(Instant.now());
            return new ApplyOutcome(attempts.save(attempt), null, null,
                    "Already applied to this posting. Nothing done.");
        }

        Path workDir = workDirFor(company, posting);
        try {
            // 1. Documents. Rendered before the browser opens, so a resume that
            //    fails to render costs nothing and leaves no half-filled form.
            TailoredResume tailored = tailor.tailor(posting);
            Path resumePdf = workDir.resolve(resumeFileName(company));
            pdf.write(renderer.toHtml(tailored, resume.headline()), resumePdf);
            attempt.setResumePath(resumePdf.toString());
            attempt.setTailoringNote(tailored.note());
            log.info("Resume: {}", tailored.note());

            try (BrowserSession session = BrowserSession.persistent(
                    Path.of(expand(config.browserProfile())), config.headless())) {

                Page page = session.newPage();
                page.setDefaultTimeout(config.pageTimeoutSeconds() * 1000.0);
                page.navigate(posting.getUrl());
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                List<FormField> fields = readFormRevealingItIfNeeded(page);
                if (fields.isEmpty()) {
                    return stop(attempt, AttemptStatus.NEEDS_HUMAN, workDir, null,
                            "No form found at " + posting.getUrl()
                                    + " - the board may require a sign-in, or the apply "
                                    + "link may lead somewhere else. The browser is open.");
                }

                // 2. The letter, only if there is a box for it. See CoverLetterWriter.
                Optional<FormField> letterBox = fields.stream()
                        .filter(f -> f.kind() == FieldKind.COVER_LETTER_TEXT)
                        .findFirst();
                String letter = letterBox
                        .flatMap(box -> letters.write(posting, company, tailored, 0))
                        .orElse(null);
                if (letterBox.isEmpty()) {
                    log.info("No cover letter text box on this form - none written");
                }
                attempt.setCoverLetter(letter);

                ApplicationDocuments documents =
                        new ApplicationDocuments(resumePdf, letter, tailored.note());

                // 3. Fill. Nothing in this call can submit.
                FillReport report = filler.fill(page, fields, posting, documents);
                attempt.setFieldLog(renderFieldLog(report));

                Path screenshot = workDir.resolve("filled-form.png");
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(screenshot).setFullPage(true));
                attempt.setScreenshotPath(screenshot.toString());

                Path review = writeReviewFile(
                        workDir, posting, company, tailored, letter, report);

                if (!report.isSubmittable()) {
                    String reason = report.blockers().stream()
                            .map(b -> b.field().label())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("a field failed to fill");
                    attempt.setBlockerReason(reason);
                    attempt.setStatus(AttemptStatus.NEEDS_HUMAN);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Filled " + report.filledCount() + " field(s), but cannot submit: "
                                    + reason + "\nThe browser is open - finish it by hand, "
                                    + "then add the answer to applicant.yml so the next one "
                                    + "does not stop here.");
                }

                if (!wantsSubmit) {
                    attempt.setStatus(AttemptStatus.PREPARED);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Prepared and filled " + report.filledCount()
                                    + " field(s). Nothing submitted.\nReview: " + review);
                }

                // 4. The only place an application is sent.
                if (!confirmSubmit.getAsBoolean()) {
                    attempt.setStatus(AttemptStatus.PREPARED);
                    attempt.setFinishedAt(Instant.now());
                    return new ApplyOutcome(attempts.save(attempt), report, review,
                            "Not submitted. The form stays filled in the open browser.");
                }

                String landedOn = submitter.submit(page, report, true);
                attempt.setStatus(AttemptStatus.SUBMITTED);
                attempt.setFinishedAt(Instant.now());

                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(workDir.resolve("after-submit.png")).setFullPage(true));

                recordInTracker(attempt, posting, company);
                return new ApplyOutcome(attempts.save(attempt), report, review,
                        "Submitted. Landed on " + landedOn);
            }

        } catch (Submitter.CaptchaPresentException e) {
            return stop(attempt, AttemptStatus.NEEDS_HUMAN, workDir, e.getMessage(), e.getMessage());
        } catch (Exception e) {
            log.error("Application failed", e);
            return stop(attempt, AttemptStatus.FAILED, workDir, e.getMessage(),
                    "Failed: " + e.getMessage());
        }
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

        TailoredResume tailored = tailor.tailor(posting);
        Path resumePdf = workDir.resolve(resumeFileName(company));
        pdf.write(renderer.toHtml(tailored, resume.headline()), resumePdf);

        attempt.setStatus(AttemptStatus.SKIPPED);
        attempt.setResumePath(resumePdf.toString());
        attempt.setTailoringNote(tailored.note());
        attempt.setBlockerReason("resume-only run - no form was opened");
        attempt.setFinishedAt(Instant.now());

        return new ApplyOutcome(attempts.save(attempt), null, resumePdf,
                tailored.note() + "\n" + resumePdf);
    }

    /**
     * Some boards put the form behind an "Apply" button on the description page.
     *
     * <p>Reading zero fields is therefore not proof there is no form. One click on
     * something that clearly means "show me the form" is worth trying; anything
     * beyond that is guessing at a page's structure, and the tool stops and says
     * so instead.
     */
    private List<FormField> readFormRevealingItIfNeeded(Page page) {
        List<FormField> fields = reader.read(page);
        if (!fields.isEmpty()) {
            return fields;
        }
        for (String name : List.of("Apply for this job", "Apply now", "Apply")) {
            Locator button = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(name).setExact(false));
            if (button.count() == 0) {
                button = page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName(name).setExact(false));
            }
            if (button.count() > 0 && button.first().isVisible()) {
                log.info("No form on the page - clicking '{}'", name);
                button.first().click();
                page.waitForLoadState(LoadState.NETWORKIDLE);
                return reader.read(page);
            }
        }
        return List.of();
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

    private ApplyOutcome stop(
            ApplicationAttempt attempt, AttemptStatus status,
            Path workDir, String blocker, String message) {
        attempt.setStatus(status);
        attempt.setBlockerReason(blocker);
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
            Path workDir, Posting posting, String company, TailoredResume tailored,
            String letter, FillReport report) throws IOException {

        StringBuilder out = new StringBuilder();
        out.append("# ").append(company).append(" — ").append(posting.getTitle()).append("\n\n")
                .append("- Posting: ").append(posting.getUrl()).append('\n')
                .append("- Location: ").append(nullSafe(posting.getLocation()))
                .append("  (").append(posting.getCountry()).append(")\n")
                .append("- Stated pay: ").append(orNone(posting.getSalaryText())).append('\n')
                .append("- Sponsorship language: ")
                .append(orNone(posting.getSponsorshipSignal())).append('\n')
                .append("- Resume tailoring: ").append(tailored.note()).append("\n\n");

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
