package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.config.BoardTokenSeeder;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.FetchResult;
import com.anuragbhandary.jobradar.fetch.FetchService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code fetch} - read boards into the database. */
@Component
public class FetchCommand {

    private final FetchService fetchService;
    private final BoardTokenSeeder seeder;

    public FetchCommand(FetchService fetchService, BoardTokenSeeder seeder) {
        this.fetchService = fetchService;
        this.seeder = seeder;
    }

    public void run(Map<String, String> options) {
        seeder.seed();

        String sourceOption = options.get("source");
        String token = options.get("token");

        Source source = null;
        if (sourceOption != null) {
            try {
                source = Source.valueOf(sourceOption.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.out.println("Unknown source: " + sourceOption
                        + ". Expected one of " + List.of(Source.values()));
                return;
            }
        }

        if (token != null && source == null) {
            System.out.println("--token requires --source");
            return;
        }

        List<FetchResult> results;
        if (token != null) {
            results = fetchService.fetchOne(source, token);
        } else if (source != null) {
            results = fetchService.fetchSource(source);
        } else {
            results = fetchService.fetchAll();
        }

        summarise(results);
    }

    private static void summarise(List<FetchResult> results) {
        int fetched = results.stream().mapToInt(FetchResult::fetched).sum();
        int created = results.stream().mapToInt(FetchResult::created).sum();
        int updated = results.stream().mapToInt(FetchResult::updated).sum();
        List<FetchResult> failed = results.stream().filter(FetchResult::failed).toList();

        System.out.printf("%n%d boards | %d postings | %d new | %d updated | %d failed%n",
                results.size(), fetched, created, updated, failed.size());

        if (!failed.isEmpty()) {
            // Named individually rather than counted, because a board that
            // starts failing looks exactly like a board with no matching jobs
            // and the difference is the whole point of tracking board health.
            System.out.println("\nFailed boards:");
            failed.forEach(r -> System.out.printf("  %-24s %s%n", r.boardToken(), r.error()));
        }
    }
}
