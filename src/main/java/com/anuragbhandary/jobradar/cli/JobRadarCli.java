package com.anuragbhandary.jobradar.cli;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Command dispatcher.
 *
 * <p>An {@code ApplicationRunner} rather than Picocli: the command surface is
 * six verbs and two options, which is well under the point where a parsing
 * library earns its dependency.
 */
@Component
public class JobRadarCli implements ApplicationRunner {

    private final FetchCommand fetch;
    private final ScreenCommand screen;
    private final DigestCommand digest;
    private final RunCommand runCommand;

    public JobRadarCli(FetchCommand fetch, ScreenCommand screen,
            DigestCommand digest, RunCommand runCommand) {
        this.fetch = fetch;
        this.screen = screen;
        this.digest = digest;
        this.runCommand = runCommand;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> positional = args.getNonOptionArgs();
        if (positional.isEmpty()) {
            printUsage();
            return;
        }

        // Spring's own option parsing is not used here so that the same flag
        // syntax works whether the app is run by java -jar or spring-boot:run.
        Map<String, String> options = parseOptions(args.getSourceArgs());
        String command = positional.getFirst();

        switch (command) {
            case "fetch" -> fetch.run(options);
            case "screen" -> screen.run(options);
            case "digest" -> digest.run(options);
            case "run" -> runCommand.run(options);
            case "probe", "sheet-append" ->
                    System.out.println("'" + command + "' is not implemented yet.");
            default -> {
                System.out.println("Unknown command: " + command);
                printUsage();
            }
        }
    }

    private static Map<String, String> parseOptions(String[] argv) {
        Map<String, String> options = new HashMap<>();
        Arrays.stream(argv)
                .filter(a -> a.startsWith("--"))
                .forEach(a -> {
                    String body = a.substring(2);
                    int eq = body.indexOf('=');
                    if (eq < 0) {
                        options.put(body, "true");
                    } else {
                        options.put(body.substring(0, eq), body.substring(eq + 1));
                    }
                });
        return options;
    }

    private void printUsage() {
        System.out.print("""
                job-radar - daily job board delta

                Usage:
                  fetch                                   fetch all active boards
                  fetch --source=GREENHOUSE               fetch one ATS
                  fetch --source=GREENHOUSE --token=stripe   fetch one board
                  screen                                  apply filters, set verdicts
                  digest                                  write today's digest
                  run                                     fetch + screen + digest
                  probe --tokens=a,b,c                    test candidate tokens (milestone 7)
                  sheet-append --posting-id=123           append to the tracker (milestone 6)
                """);
    }
}
