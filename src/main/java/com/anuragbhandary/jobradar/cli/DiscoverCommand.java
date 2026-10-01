package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.discover.DiscoveryService;
import com.anuragbhandary.jobradar.discover.DiscoveryService.Report;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code discover} - find boards in the Internet Archive's index, survey them,
 * and add the ones with roles in India, Ireland, Germany, the Netherlands or open
 * remote.
 *
 * <pre>
 * discover [--platforms=greenhouse,lever,ashby,recruitee,smartrecruiters,workday]
 *          [--deep] [--recheck | --recheck-days=N] [--limit=N] [--dry-run]
 * </pre>
 *
 * A first run surveys about twenty thousand boards and takes an hour or more;
 * later runs survey only boards new to the archive. {@code --recheck} surveys
 * again every board not added, after a change to what counts (a new country).
 */
@Component
public class DiscoverCommand {

    private final DiscoveryService discovery;

    public DiscoverCommand(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    public void run(Map<String, String> options) {
        List<Source> sources;
        try {
            sources = options.containsKey("platforms")
                    ? Arrays.stream(options.get("platforms").split(","))
                            .map(s -> Source.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                            .toList()
                    : DiscoveryService.SOURCES;
        } catch (IllegalArgumentException e) {
            System.out.println("Unknown platform. Expected some of " + DiscoveryService.SOURCES);
            return;
        }
        if (!DiscoveryService.SOURCES.containsAll(sources)) {
            System.out.println("Discovery covers only " + DiscoveryService.SOURCES);
            return;
        }
        DiscoveryService.Options run = new DiscoveryService.Options(
                sources,
                "true".equals(options.get("deep")),
                "true".equals(options.get("recheck")) ? -1 : intOption(options, "recheck-days"),
                intOption(options, "limit"),
                "true".equals(options.get("dry-run")));

        List<Report> reports = discovery.run(run);

        System.out.printf("%n%-16s %9s %7s %9s %6s %7s%n",
                "platform", "archive", "known", "surveyed", "added", "failed");
        int added = 0;
        for (Report r : reports) {
            System.out.printf("%-16s %9d %7d %9d %6d %7d%s%n", r.source(), r.inArchive(),
                    r.alreadyKnown(), r.surveyed(), r.added(), r.failed(),
                    r.error() == null ? "" : "  " + r.error());
            added += r.added();
        }
        for (Report r : reports) {
            if (!r.addedBoards().isEmpty()) {
                System.out.printf("%n%s added: %s%n", r.source(), String.join(", ", r.addedBoards()));
            }
        }
        System.out.printf("%n%d board(s) %s.%n", added,
                run.dryRun() ? "would be added (dry run, nothing written)" : "added to the daily run");
    }

    private static int intOption(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank() || "true".equals(value)) {
            return 0;
        }
        return Integer.parseInt(value.trim());
    }
}
