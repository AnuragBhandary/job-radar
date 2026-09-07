package com.anuragbhandary.jobradar.cli;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code ui} - the review queue in a browser, on localhost.
 *
 * <p>The one command that starts a web layer, and the only feature that talked
 * this project into a servlet container. What earned it: the review artefacts are
 * a full-page screenshot, a PDF and a forty-line field log, and a terminal can
 * show none of them. Reviewing an application meant opening three files by path
 * from a printed line, per application.
 *
 * <p>It binds localhost only. There is no login because there is no second user;
 * that assumption is written down in {@link
 * com.anuragbhandary.jobradar.web.UiController} because it is the thing that has
 * to be revisited if the address ever changes.
 */
@Component
public class UiCommand {

    @Value("${server.port:8080}")
    private int port;

    public void run(Map<String, String> options) {
        System.out.printf("""

                job-radar review queue

                  http://localhost:%d

                Prepare an application, read the filled form, and submit from
                there. Preparing opens a real browser and takes half a minute -
                the window is not a bug, it is the form being filled.

                Ctrl-C to stop.
                """, port);

        // The servlet container is already running and is a non-daemon thread, so
        // this returns and the JVM stays up. Sleeping here would be a second way
        // to keep it alive and a first way to get it wrong.
    }
}
