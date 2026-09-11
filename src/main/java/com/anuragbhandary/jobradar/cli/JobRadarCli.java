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
 * ten verbs and a handful of options, which is still under the point where a
 * parsing library earns its dependency.
 */
@Component
public class JobRadarCli implements ApplicationRunner {

    private final FetchCommand fetch;
    private final ScreenCommand screen;
    private final DigestCommand digest;
    private final RunCommand runCommand;
    private final SheetAppendCommand sheetAppend;
    private final SheetListCommand sheetList;
    private final ProbeCommand probe;
    private final ServeCommand serve;
    private final ApplyCommand apply;
    private final ApplicationsCommand applications;
    private final FollowUpCommand followUp;
    private final LearnCommand learn;
    private final InboxCommand inbox;
    private final LoginCommand login;
    private final UiCommand ui;
    private final PrepCommand prep;
    private final VariantsCommand variants;
    private final BoardCommand board;
    private final KnowledgeCommand knowledge;
    private final BenchLlmCommand benchLlm;
    private final LedgerCommand ledger;
    private final BenchRewriteCommand benchRewrite;
    private final EvidenceCommand evidence;

    public JobRadarCli(FetchCommand fetch, ScreenCommand screen,
            DigestCommand digest, RunCommand runCommand,
            SheetAppendCommand sheetAppend, SheetListCommand sheetList,
            ProbeCommand probe, ServeCommand serve,
            ApplyCommand apply, ApplicationsCommand applications,
            FollowUpCommand followUp, LearnCommand learn, InboxCommand inbox, LoginCommand login, UiCommand ui, PrepCommand prep, VariantsCommand variants, BoardCommand board,
            KnowledgeCommand knowledge, BenchLlmCommand benchLlm, LedgerCommand ledger,
            BenchRewriteCommand benchRewrite, EvidenceCommand evidence) {
        this.fetch = fetch;
        this.screen = screen;
        this.digest = digest;
        this.runCommand = runCommand;
        this.sheetAppend = sheetAppend;
        this.sheetList = sheetList;
        this.probe = probe;
        this.serve = serve;
        this.apply = apply;
        this.applications = applications;
        this.followUp = followUp;
        this.learn = learn;
        this.inbox = inbox;
        this.login = login;
        this.ui = ui;
        this.prep = prep;
        this.variants = variants;
        this.board = board;
        this.knowledge = knowledge;
        this.benchLlm = benchLlm;
        this.ledger = ledger;
        this.benchRewrite = benchRewrite;
        this.evidence = evidence;
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
            case "sheet-append" -> sheetAppend.run(options);
            case "sheet-list" -> sheetList.run(options);
            case "probe" -> probe.run(options);
            case "serve" -> serve.run(options);
            case "apply" -> apply.run(options);
            case "applications" -> applications.run(options);
            case "follow-up" -> followUp.run(options);
            case "learn" -> learn.run(options);
            case "inbox" -> inbox.run(options);
            case "login" -> login.run(options);
            case "ui" -> ui.run(options);
            case "prep" -> prep.run(options);
            case "variants" -> variants.run(options);
            case "board" -> board.run(options);
            case "knowledge" -> knowledge.run(options);
            case "bench-llm" -> benchLlm.run(options);
            case "ledger" -> ledger.run(options);
            case "bench-rewrite" -> benchRewrite.run(options);
            case "evidence" -> evidence.run(options);
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
                  probe --tokens=a,b,c [--add]            test candidate tokens
                  serve                                   stay running for the daily schedule
                  sheet-list                              print the tracker (read-only)
                  sheet-append --posting-id=123           append to the tracker

                Applying:
                  apply --posting-id=123                  tailor, fill, stop before submit
                  apply --posting-id=123 --submit         the same, then ask before sending
                  apply --posting-id=123 --resume-only    tailor the resume only, no browser
                  apply --all [--limit=5]                 prepare the candidate list
                  applications [--status=NEEDS_HUMAN]     what has been prepared or sent
                  follow-up [--days=14]                   applications that have gone quiet
                  follow-up --close-abandoned             mark six-week silences "No response"
                  learn [--write]                         questions that blocked forms, as YAML
                  inbox [--days=60] [--apply]             read replies, update tracker status
                  login --url=... | --list                sign in to a board by hand, once
                  ui                                      review queue at http://localhost:8080
                  prep --posting-id=123 [--print]         interview prep: gaps, questions, answers
                  variants                                which resume opening gets replies
                  board [--import]                        the pipeline; --import seeds it from the sheet

                Knowledge (the resolver answers real forms; --shadow diffs it against the legacy mapper):
                  knowledge                               concepts and stored assertions
                  knowledge --migrate                     import applicant.yml's extra-answers
                  knowledge --review                      answers migrated without a scope
                  knowledge --shadow [--verbose]          diff the resolver across the whole corpus
                  knowledge --shadow --attempts           diff it over the recorded applications only
                  knowledge --explain="..." [--posting-id=N]   answer one question, with reasoning

                Evidence bank (the approved facts every resume is planned from):
                  evidence                                what the bank holds, and whether it matches the resume
                  evidence --check                        every problem in the file, and what it means for tailoring
                  evidence --for="event-driven systems"   the strongest evidence for one requirement
                  evidence --plan --posting-id=123 [--html=FILE]
                                                          the resume plan for a posting; --html renders it (no PDF)

                Resume analysis (experiments - nothing here changes a resume or an application):
                  ledger --posting-id=123 [--verbose]     each requirement, its evidence level, its source ids
                  ledger --bench [--model=NAME[@N]]       the same over the benchmark set; --model reads the
                                                          requirements with a local model (grounded ones only)
                  ledger --sources                        every resume source id a ledger can cite
                  bench-llm [--models=a,b] [--limit=N]    compare local Ollama models on requirement extraction;
                                                          name@N caps a model at N GPU layers (qwen3.6:27b@45)
                  bench-rewrite [--posting-ids=a,b]       shadow benchmark: local-model bullet rewrites, validated
                                                          in Java, beside the deterministic resume. Never used
                                                          by apply

                `apply` never sends anything on its own. --submit asks at the
                terminal once the form is filled, and --all refuses --submit.
                """);
    }
}
