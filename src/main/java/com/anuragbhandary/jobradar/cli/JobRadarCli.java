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
 * ten verbs and a handful of options, which is under the point where a parsing
 * library earns its dependency.
 */
@Component
public class JobRadarCli implements ApplicationRunner {

    private final FetchCommand fetch;
    private final ScreenCommand screen;
    private final DigestCommand digest;
    private final RunCommand runCommand;
    private final MarkCommand mark;
    private final ResumeCommand resume;
    private final SheetAppendCommand sheetAppend;
    private final SheetListCommand sheetList;
    private final ProbeCommand probe;
    private final NotifyCommand notify;

    public JobRadarCli(FetchCommand fetch, ScreenCommand screen, DigestCommand digest,
            RunCommand runCommand, MarkCommand mark, ResumeCommand resume,
            SheetAppendCommand sheetAppend, SheetListCommand sheetList, ProbeCommand probe,
            NotifyCommand notify) {
        this.fetch = fetch;
        this.screen = screen;
        this.digest = digest;
        this.runCommand = runCommand;
        this.mark = mark;
        this.resume = resume;
        this.sheetAppend = sheetAppend;
        this.sheetList = sheetList;
        this.probe = probe;
        this.notify = notify;
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
            case "export" -> digest.export(options);
            case "openings" -> digest.openings(options);
            case "run" -> runCommand.run(options);
            case "mark" -> mark.run(args, options);
            case "resume" -> resume.run(options);
            case "sheet-append" -> sheetAppend.run(options);
            case "sheet-list" -> sheetList.run(options);
            case "sheet-set" -> sheetAppend.set(options);
            case "probe" -> probe.run(options);
            case "notify" -> notify.run(options);
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
                job-radar - fetch and screen job boards; judging happens in chat (see CLAUDE.md)

                Daily:
                  openings [--since=YYYY-MM-DD]           what became worth reviewing since the last review,
                                                          plus the shortlist not yet applied to
                  openings --done                         record that review, so it is not shown again
                  run                                     fetch + screen + tracker sync + handoff file
                  fetch [--source=GREENHOUSE [--token=stripe]]   fetch all boards, one ATS, or one board
                  screen                                  apply the fact-based filters, set verdicts
                  digest                                  write today's handoff file to inbox/
                  export --since=YYYY-MM-DD               handoff file for every open candidate since a day

                  notify [--ids=a,b,c] [--dry-run]        post the shortlist (or these ids, in order)
                                                          to the Discord webhook in secrets.yml

                Decisions:
                  mark <id>[,<id>...] <decision> [--note="..."]
                                                          shortlist | skip | applied | screening |
                                                          interview | offer | rejected | withdrawn;
                                                          applied and later go to the tracker sheet
                  sheet-list                              print the tracker (read-only)
                  sheet-append --posting-id=123 [--yes]   append one row to the tracker

                Resume:
                  resume --list                           every summary and bullet, with references
                  resume [--summary=ID] [--pick=e1.3,e1.1,p2.1] [--out=FILE.pdf] [--html]
                                                          render applicant.yml in the chosen order

                Boards:
                  probe --tokens=a,b,c [--add]            test candidate tokens on every ATS; --add saves hits
                """);
    }
}
