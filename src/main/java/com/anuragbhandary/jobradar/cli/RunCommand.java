package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.pipeline.PipelineService;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code run} - the daily job: fetch, screen, sync the tracker, write the handoff file.
 *
 * <p>The sync comes before the file so that an application recorded straight into
 * the spreadsheet, rather than through {@code mark}, still takes its posting out of
 * the next handoff file. It is idempotent and never overwrites a stage set here.
 */
@Component
public class RunCommand {

    private final FetchCommand fetch;
    private final ScreenCommand screen;
    private final PipelineService pipeline;
    private final DigestCommand digest;

    public RunCommand(FetchCommand fetch, ScreenCommand screen, PipelineService pipeline,
            DigestCommand digest) {
        this.fetch = fetch;
        this.screen = screen;
        this.pipeline = pipeline;
        this.digest = digest;
    }

    public void run(Map<String, String> options) {
        fetch.run(options);
        screen.run(options);
        try {
            System.out.println("\nTracker sync: " + pipeline.importFromTracker().describe());
        } catch (IOException | RuntimeException e) {
            // The file is still worth writing; it just may list something already
            // applied to, and it says which companies those are.
            System.out.println("\nTracker sync failed, continuing: " + e.getMessage());
        }
        digest.run(options);
    }
}
