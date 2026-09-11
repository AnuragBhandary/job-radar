package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.plan.ResumePipeline;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch;
import com.anuragbhandary.jobradar.evidence.EvidenceProblem;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.evidence.ResumeConsistency;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code evidence} - what the evidence bank holds, whether it can be used, and what
 * it would put on a resume.
 *
 * <p>Read-only. Nothing here writes the bank, the database or an application; with
 * {@code --html} it writes one rendered resume where it is told to, and no PDF, so
 * no browser is started.
 */
@Component
public class EvidenceCommand {

    private final EvidenceBank bank;
    private final ResumeModel resume;
    private final PostingRepository postings;
    private final ResumePipeline pipeline;
    private final ResumeRenderer renderer;

    public EvidenceCommand(EvidenceBank bank, ResumeModel resume, PostingRepository postings,
            ResumePipeline pipeline, ResumeRenderer renderer) {
        this.bank = bank;
        this.resume = resume;
        this.postings = postings;
        this.pipeline = pipeline;
        this.renderer = renderer;
    }

    public void run(Map<String, String> options) {
        System.out.print(execute(options));
    }

    String execute(Map<String, String> options) {
        if (options.containsKey("for")) {
            return strongest(options.get("for"), intOption(options, "limit", 5));
        }
        if (options.containsKey("plan")) {
            String id = options.get("posting-id");
            if (id == null || "true".equals(id)) {
                return "Usage: evidence --plan --posting-id=N [--html=FILE]\n";
            }
            Optional<Posting> posting = postings.findById(Long.parseLong(id.strip()));
            if (posting.isEmpty()) {
                return "No posting " + id + "\n";
            }
            return plan(posting.get(), options.get("html"));
        }
        if (options.containsKey("check")) {
            return check();
        }
        return summary();
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Evidence bank: ").append(bank.origin()).append('\n');
        long approved = bank.items().stream().mapToLong(i -> i.approvedVariants().size()).sum();
        long proposed = bank.items().stream()
                .mapToLong(i -> i.variants().size() - i.approvedVariants().size()).sum();
        sb.append(String.format(Locale.ROOT, "  %d source(s), %d item(s), %d approved variant(s), "
                + "%d proposed and not approved%n", bank.sources().size(), bank.items().size(),
                approved, proposed));
        for (EvidenceSource source : bank.sources()) {
            sb.append(String.format(Locale.ROOT, "  %-14s %-10s %-44s %d item(s)%n", source.id(),
                    source.kind().name().toLowerCase(Locale.ROOT), source.name(),
                    bank.itemsFrom(source.id()).size()));
        }
        long errors = bank.problems().stream().filter(EvidenceProblem::isError).count();
        ResumeConsistency consistency = ResumeConsistency.check(bank, resume);
        sb.append(String.format(Locale.ROOT, "  %d problem(s) in the file (%d error(s))%n",
                bank.problems().size(), errors));
        sb.append("  ").append(verdict(consistency)).append('\n');
        sb.append("Run `evidence --check` for every problem.\n");
        return sb.toString();
    }

    String check() {
        StringBuilder sb = new StringBuilder("Evidence bank: " + bank.origin() + "\n");
        if (bank.problems().isEmpty()) {
            sb.append("  The file has no problems.\n");
        } else {
            sb.append("  The file:\n");
            bank.problems().forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        ResumeConsistency consistency = ResumeConsistency.check(bank, resume);
        if (consistency.problems().isEmpty()) {
            sb.append("  Against the resume: consistent.\n");
        } else {
            sb.append("  Against the resume:\n");
            consistency.problems().forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        sb.append("  ").append(verdict(consistency)).append('\n');
        return sb.toString();
    }

    private String verdict(ResumeConsistency consistency) {
        if (bank.isEmpty()) {
            return "Resumes are tailored as before: there is no usable evidence.";
        }
        if (!consistency.consistent()) {
            return "Resumes are tailored as before: the bank does not match the resume.";
        }
        return "Resumes are planned from the bank.";
    }

    String strongest(String requirement, int limit) {
        if (requirement == null || requirement.isBlank() || "true".equals(requirement)) {
            return "Usage: evidence --for=\"a requirement\" [--limit=5]\n";
        }
        List<EvidenceMatch> matches = bank.strongestFor(requirement, limit);
        StringBuilder sb = new StringBuilder("Strongest evidence for \"" + requirement.strip() + "\":\n");
        if (matches.isEmpty()) {
            sb.append("  Nothing in the bank supports this. It will not be claimed.\n");
            return sb.toString();
        }
        int n = 1;
        for (EvidenceMatch match : matches) {
            EvidenceItem item = match.item();
            sb.append(String.format(Locale.ROOT, "  %d. %-30s %-10s %.3f  %s%s%n", n++, item.id(),
                    match.kind().name().toLowerCase(Locale.ROOT), match.score(), match.reason(),
                    match.supportsClaim() ? "" : " - worth showing, not a claim of \""
                            + requirement.strip() + "\""));
            sb.append("     ").append(shorten(item.claim(), 110)).append('\n');
        }
        return sb.toString();
    }

    String plan(Posting posting, String html) {
        TailoringPlan plan = pipeline.tailor(posting);
        StringBuilder sb = new StringBuilder("Posting " + posting.getId() + ": " + posting.getTitle() + "\n");
        sb.append(plan.explain());
        if (html != null) {
            Path out = "true".equals(html)
                    ? Path.of("build", "reports", "resume-plan-" + posting.getId() + ".html")
                    : Path.of(html);
            try {
                if (out.toAbsolutePath().getParent() != null) {
                    Files.createDirectories(out.toAbsolutePath().getParent());
                }
                Files.writeString(out, renderer.toHtml(plan.resume(), resume.headline()));
                sb.append("Wrote ").append(out.toAbsolutePath()).append('\n');
            } catch (IOException e) {
                sb.append("Could not write the resume: ").append(e.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        try {
            return Integer.parseInt(options.getOrDefault(name, String.valueOf(fallback)).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String shorten(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
