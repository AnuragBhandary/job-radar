package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.resume.PdfPrinter;
import com.anuragbhandary.jobradar.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.resume.ResumeSelection;
import com.anuragbhandary.jobradar.resume.ResumeSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code resume} - render the resume from {@code applicant.yml} in a chosen order.
 *
 * <pre>
 *   resume --list                                  every summary and bullet, with references
 *   resume [--summary=ID] [--pick=e1.3,e1.1,p2.1] [--out=FILE.pdf] [--html]
 * </pre>
 *
 * <p>The choosing happens in chat; this prints it. It can only reorder and omit
 * what the file already says, so nothing it produces is a claim he has not written.
 */
@Component
public class ResumeCommand {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final ResumeSource source;
    private final ResumeRenderer renderer;
    private final PdfPrinter printer;

    public ResumeCommand(ResumeSource source, ResumeRenderer renderer, PdfPrinter printer) {
        this.source = source;
        this.renderer = renderer;
        this.printer = printer;
    }

    public void run(Map<String, String> options) {
        if (source == null || source.isEmpty()) {
            System.out.println("No resume found. It is read from job-radar.resume in "
                    + "~/.config/job-radar/applicant.yml.");
            return;
        }
        if (options.containsKey("list")) {
            list();
            return;
        }

        List<String> picks = options.containsKey("pick")
                ? Arrays.asList(options.get("pick").split(","))
                : List.of();
        ResumeSelection.Selected selected;
        try {
            selected = new ResumeSelection(options.get("summary"), picks).apply(source);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }

        String html = renderer.toHtml(selected);
        boolean htmlOnly = options.containsKey("html");
        Path out = Path.of(options.getOrDefault("out",
                "resumes/resume-" + LocalDateTime.now().format(STAMP)
                        + (htmlOnly ? ".html" : ".pdf")));
        try {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            if (htmlOnly) {
                Files.writeString(out, html, StandardCharsets.UTF_8);
            } else {
                printer.print(html, out);
            }
            System.out.println("Written to " + out.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.out.println("Could not write the resume: " + e.getMessage());
        }
    }

    private void list() {
        System.out.println("\nSummaries (--summary=ID):");
        source.summaries().forEach(s -> System.out.println("  " + s.id() + ": " + s.text()));
        for (int j = 0; j < source.experience().size(); j++) {
            ResumeSource.Job job = source.experience().get(j);
            System.out.printf("%nJob e%d: %s · %s (default: first %d)%n", j + 1, job.title(),
                    job.company(), source.maxBulletsPerJob());
            for (int b = 0; b < job.bullets().size(); b++) {
                System.out.printf("  e%d.%d  %s%n", j + 1, b + 1, job.bullets().get(b).text());
            }
        }
        for (int p = 0; p < source.projects().size(); p++) {
            ResumeSource.Project project = source.projects().get(p);
            System.out.printf("%nProject p%d: %s (%s)%n", p + 1, project.name(), project.stack());
            for (int b = 0; b < project.bullets().size(); b++) {
                System.out.printf("  p%d.%d  %s%n", p + 1, b + 1, project.bullets().get(b).text());
            }
        }
        System.out.printf("%nWith no project picked, the first %d projects print.%n",
                source.maxProjects());
    }
}
