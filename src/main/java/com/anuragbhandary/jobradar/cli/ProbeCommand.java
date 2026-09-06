package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.fetch.ProbeResult;
import com.anuragbhandary.jobradar.fetch.TokenProber;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code probe --tokens=a,b,c} - test candidate tokens across the four company
 * board platforms.
 *
 * <p>{@code --add} seeds anything found as an active board. Off by default: a
 * probe is a guess, and a wrong guess that quietly joins the daily run is worse
 * than one that does not.
 */
@Component
public class ProbeCommand {

    private final TokenProber prober;
    private final BoardTokenRepository boards;

    public ProbeCommand(TokenProber prober, BoardTokenRepository boards) {
        this.prober = prober;
        this.boards = boards;
    }

    public void run(Map<String, String> options) {
        String tokens = options.get("tokens");
        if (tokens == null || tokens.isBlank()) {
            System.out.println("Usage: probe --tokens=acme,globex [--add]");
            return;
        }
        boolean add = "true".equals(options.get("add"));

        List<String> candidates = Arrays.stream(tokens.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        System.out.printf("%nProbing %d token(s) across Greenhouse, Ashby, Lever "
                + "and SmartRecruiters%n%n", candidates.size());

        int found = 0;
        for (String candidate : candidates) {
            List<ProbeResult> results = prober.probe(candidate);
            System.out.println("  " + candidate);

            for (ProbeResult result : results) {
                System.out.printf("    %-16s %-14s %s%n",
                        result.source() == null ? "-" : result.source(),
                        result.outcome(),
                        describe(result));
                if (result.isInteresting()) {
                    found++;
                    if (add) {
                        boards.save(new BoardToken(
                                result.source(), result.token(), candidate));
                        System.out.printf("    %-16s %s%n", "", "-> added as an active board");
                    }
                }
            }
            System.out.println();
        }

        System.out.printf("%d live board(s) found.%n", found);
        if (found > 0 && !add) {
            System.out.println("Re-run with --add to start fetching them daily.");
        }
    }

    private static String describe(ProbeResult result) {
        if (result.outcome() == ProbeResult.Outcome.FOUND) {
            return result.postings() + " postings";
        }
        return result.detail() == null ? "" : result.detail();
    }
}
