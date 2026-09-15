package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.resume.ComposedResume;
import com.anuragbhandary.jobradar.apply.resume.ProfileMigration;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.plan.ResumePipeline;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlan;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceMatch;
import com.anuragbhandary.jobradar.evidence.EvidenceProblem;
import com.anuragbhandary.jobradar.evidence.EvidenceReadiness;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code evidence} - what the evidence bank holds, whether applications can be
 * generated from it, and what it would put on a resume.
 *
 * <p>Read-only except for {@code --migrate-profile}, which removes the duplicate
 * bullets and projects from applicant.yml once the bank is confirmed to hold them,
 * keeping a backup. {@code --plan --html} writes one rendered resume and no PDF, so
 * no browser is started.
 */
@Component
public class EvidenceCommand {

    /** How application.yml imports the profile; resolved the same way here. */
    static final String PROFILE = "${JOB_RADAR_PROFILE:${user.home}/.config/job-radar/applicant.yml}";

    private final EvidenceBank bank;
    private final ComposedResume composed;
    private final EvidenceReadiness readiness;
    private final PostingRepository postings;
    private final ResumePipeline pipeline;
    private final ResumeRenderer renderer;
    private final ResumeModel resume;
    private final Environment environment;

    public EvidenceCommand(EvidenceBank bank, ComposedResume composed, EvidenceReadiness readiness,
            PostingRepository postings, ResumePipeline pipeline, ResumeRenderer renderer,
            ResumeModel resume, Environment environment) {
        this.bank = bank;
        this.composed = composed;
        this.readiness = readiness;
        this.postings = postings;
        this.pipeline = pipeline;
        this.renderer = renderer;
        this.resume = resume;
        this.environment = environment;
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
        if (options.containsKey("migrate-profile")) {
            return migrate("dry".equals(options.get("migrate-profile")));
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
        sb.append(String.format(Locale.ROOT, "  %d problem(s) in the file (%d error(s)); "
                + "%d joining it to applicant.yml%n", bank.problems().size(), errors,
                composed.problems().size()));
        sb.append("  ").append(verdict()).append('\n');
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
        if (composed.problems().isEmpty()) {
            sb.append("  Joined to applicant.yml: no problems.\n");
        } else {
            sb.append("  Joined to applicant.yml:\n");
            composed.problems().forEach(p -> sb.append("    ").append(p).append('\n'));
        }
        sb.append("  ").append(verdict()).append('\n');
        return sb.toString();
    }

    private String verdict() {
        List<String> blockers = readiness.blockers();
        if (blockers.isEmpty()) {
            return "Applications are generated from the bank.";
        }
        return "Applications will not be generated until this is fixed: " + blockers.getFirst()
                + (blockers.size() > 1 ? " (+" + (blockers.size() - 1) + " more)" : "");
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
        StringBuilder sb = new StringBuilder("Posting " + posting.getId() + ": " + posting.getTitle() + "\n");
        TailoringPlan plan;
        try {
            plan = pipeline.tailor(posting);
        } catch (EvidenceIntegrityException e) {
            return sb.append("Nothing generated. ").append(e.getMessage()).append('\n').toString();
        }
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

    /**
     * Removes the duplicate bullets and projects from applicant.yml, keeping a backup.
     * Refuses, and writes nothing, unless the bank holds every one of them.
     */
    String migrate(boolean dry) {
        Path file = Path.of(environment.resolvePlaceholders(PROFILE));
        if (!Files.isRegularFile(file)) {
            return "No applicant.yml at " + file + " - nothing to migrate.\n";
        }
        if (bank.isEmpty()) {
            return "The evidence bank is unavailable, so nothing in applicant.yml can be confirmed "
                    + "as held by it. Fix the bank first (`evidence --check`).\n";
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return "Could not read " + file + ": " + e.getMessage() + "\n";
        }
        ProfileMigration.Result result = ProfileMigration.migrate(text, bank);
        StringBuilder sb = new StringBuilder("applicant.yml: " + file + "\n");
        if (!result.ok()) {
            sb.append("  Not migrated, nothing written:\n");
            result.problems().forEach(p -> sb.append("    ").append(p).append('\n'));
            return sb.toString();
        }
        if (!result.changed()) {
            return sb.append("  Already migrated: it holds no resume bullets and no projects.\n").toString();
        }
        sb.append(dry ? "  Would remove (the evidence bank holds every one):\n"
                : "  Removed (the evidence bank holds every one):\n");
        result.removed().forEach(r -> sb.append("    ").append(r).append('\n'));
        if (dry) {
            return sb.append("  Dry run - nothing written. Run `evidence --migrate-profile` to apply.\n")
                    .toString();
        }
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".pre-evidence-bank.bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, result.text());
            sb.append("  Backup: ").append(backup).append('\n')
                    .append("  Every other key and comment is unchanged. The next run reads the new file.\n");
        } catch (IOException e) {
            sb.append("  Could not write it: ").append(e.getMessage()).append('\n');
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
