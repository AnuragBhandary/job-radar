package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.pipeline.PipelineService;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
    private final BoardTokenRepository boards;

    /** Empty for this long, a board is worth a look rather than a shrug. */
    static final int EMPTY_WARNING_DAYS = 3;

    public RunCommand(FetchCommand fetch, ScreenCommand screen, PipelineService pipeline,
            DigestCommand digest, BoardTokenRepository boards) {
        this.fetch = fetch;
        this.screen = screen;
        this.pipeline = pipeline;
        this.digest = digest;
        this.boards = boards;
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
        warnAboutEmptyBoards();
    }

    /**
     * Boards that have answered with nothing for days. Never disabled here: an
     * empty board costs a request and no tokens, and some companies do go quiet
     * between hiring rounds. It is named so somebody checks whether it moved.
     */
    private void warnAboutEmptyBoards() {
        Instant now = Instant.now();
        List<BoardToken> quiet = boards.findByActiveTrue().stream()
                .filter(b -> b.daysEmpty(now) >= EMPTY_WARNING_DAYS)
                .toList();
        if (quiet.isEmpty()) {
            return;
        }
        System.out.println("\nEmpty for " + EMPTY_WARNING_DAYS + "+ days (dead token, or the "
                + "company moved boards?):");
        quiet.forEach(b -> System.out.println("  " + b.getSource() + "/" + b.getToken()
                + " - " + b.daysEmpty(now) + " days"));
    }
}
