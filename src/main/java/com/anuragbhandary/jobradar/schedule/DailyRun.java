package com.anuragbhandary.jobradar.schedule;

import com.anuragbhandary.jobradar.cli.RunCommand;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs fetch, screen and digest once a morning.
 *
 * <p>Only active under the {@code serve} command, which is what keeps the JVM
 * alive. Every other command does its work and exits, and a scheduler in a
 * process that exits in two seconds would never fire.
 *
 * <p><strong>This only fires if the process is running at the time.</strong>
 * Spring's scheduler has no memory of missed runs: if the machine is asleep or
 * off at 07:00, nothing happens then and nothing catches up afterwards. On a
 * laptop that is the normal case rather than the exception, which is why the
 * README recommends launchd - it reruns a missed calendar job when the machine
 * wakes - and why {@code run} is a plain command any scheduler can invoke.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "job-radar.schedule.enabled", havingValue = "true")
public class DailyRun {

    private static final Logger log = LoggerFactory.getLogger(DailyRun.class);

    private final RunCommand runCommand;

    public DailyRun(RunCommand runCommand) {
        this.runCommand = runCommand;
    }

    @Scheduled(cron = "${job-radar.schedule.cron:0 0 7 * * *}", zone = "Asia/Kolkata")
    public void run() {
        log.info("Scheduled daily run starting");
        try {
            runCommand.run(Map.of());
        } catch (RuntimeException e) {
            // A scheduled run that throws kills nothing but itself; the next one
            // must still happen, so this is logged rather than propagated.
            log.error("Scheduled run failed: {}", e.getMessage(), e);
        }
    }
}
