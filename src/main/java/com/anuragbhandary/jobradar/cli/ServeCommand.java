package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.config.AppProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code serve} - stay running so the daily schedule can fire.
 *
 * <p>Prints what it is waiting for and why that might not be enough. A scheduler
 * that silently does nothing because the machine was asleep is worse than no
 * scheduler, so the caveat is stated at the point of use rather than left in the
 * documentation.
 */
@Component
public class ServeCommand {

    private final AppProperties properties;
    private final String cron;
    private final boolean enabled;

    public ServeCommand(
            AppProperties properties,
            @org.springframework.beans.factory.annotation.Value(
                    "${job-radar.schedule.cron:0 0 7 * * *}") String cron,
            @org.springframework.beans.factory.annotation.Value(
                    "${job-radar.schedule.enabled:false}") boolean enabled) {
        this.properties = properties;
        this.cron = cron;
        this.enabled = enabled;
    }

    public void run(Map<String, String> options) {
        if (!enabled) {
            System.out.println("""
                    Scheduling is off. Start it with:
                      JOB_RADAR_SCHEDULE=true job-radar serve
                    """);
            return;
        }
        System.out.printf("""
                job-radar is running.

                  schedule    %s (Asia/Kolkata)
                  digests     %s
                  sheets      %s

                This only fires while this process is alive. If the machine is
                asleep or off at that time, the run is missed and is not caught
                up - see the README for launchd, which reruns a missed job on
                wake, or run it in Docker somewhere that stays on.

                Ctrl-C to stop.
                %n""",
                cron,
                properties.outputDir(),
                properties.google().isConfigured() ? "configured" : "not configured");
    }
}
