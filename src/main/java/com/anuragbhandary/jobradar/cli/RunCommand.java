package com.anuragbhandary.jobradar.cli;

import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code run} - the daily job: fetch, screen, digest. */
@Component
public class RunCommand {

    private final FetchCommand fetch;
    private final ScreenCommand screen;
    private final DigestCommand digest;

    public RunCommand(FetchCommand fetch, ScreenCommand screen, DigestCommand digest) {
        this.fetch = fetch;
        this.screen = screen;
        this.digest = digest;
    }

    public void run(Map<String, String> options) {
        fetch.run(options);
        screen.run(options);
        digest.run(options);
    }
}
