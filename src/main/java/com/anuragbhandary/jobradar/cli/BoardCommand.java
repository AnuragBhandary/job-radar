package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.pipeline.PipelineService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code board} - the pipeline, in the terminal.
 *
 * <p>{@code --import} seeds it from the spreadsheet. Worth running once: the
 * tracker holds applications made before any of this existed, and a board that
 * started empty would be a worse record than the sheet on its first day.
 */
@Component
public class BoardCommand {

    private final PipelineService pipeline;

    public BoardCommand(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    public void run(Map<String, String> options) {
        if ("true".equals(options.get("import"))) {
            try {
                System.out.printf("%n%s%n", pipeline.importFromTracker().describe());
            } catch (IOException e) {
                System.out.println("Could not read the tracker: " + e.getMessage());
                return;
            }
        }

        LocalDate today = LocalDate.now();
        System.out.println();
        for (PipelineService.Column column : pipeline.board()) {
            if (column.size() == 0) {
                continue;
            }
            System.out.printf("%s  (%d)%n", column.stage().label().toUpperCase(), column.size());
            for (PipelineService.Entry entry : column.entries()) {
                String score = entry.score() == null ? "  — "
                        : String.format("%3d ", entry.score().score());
                System.out.printf("  %s %-26s %-44s%s%n",
                        score,
                        truncate(entry.interest().getCompany(), 26),
                        truncate(entry.interest().getRole(), 44),
                        entry.isDue(today) ? "  ← follow up" : "");
            }
            System.out.println();
        }

        var due = pipeline.dueReminders(today);
        if (!due.isEmpty()) {
            System.out.printf("%d reminder(s) due.%n", due.size());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
