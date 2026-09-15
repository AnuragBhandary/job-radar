package com.anuragbhandary.jobradar.apply.resume;

import static com.anuragbhandary.jobradar.evidence.EvidenceProblem.error;
import static com.anuragbhandary.jobradar.evidence.EvidenceProblem.warning;

import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceProblem;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the runtime {@link ResumeModel} from the profile's presentation and the
 * evidence bank's claims.
 *
 * <pre>
 *   applicant.yml (ResumeProfile)        evidence.yml (EvidenceBank)
 *     headline, summaries, skills          employment sources → items → claims
 *     jobs: company, title, dates, note    project sources → stack line, items
 *     education, extras, caps
 *                   └────────── ResumeComposer ──────────┘
 *                                    ▼
 *                     ResumeModel: every bullet a bank claim, with its id
 * </pre>
 *
 * <p>Everything that reads the resume - the tailor, the planner, the coverage
 * ledger's source ids, the experience index behind form answers, the match score -
 * reads this, so none of them can see a claim the bank does not hold.
 *
 * <p>Order is the bank's: items in the order the file lists them, projects in the
 * order its project sources appear. A bullet's tags are its item's technologies and
 * concepts, which is what the old hand-written tags were for, now grounded.
 *
 * <p>Nothing here repairs or guesses. A job with no source prints with no bullets
 * and a blocking problem; a bullet left in applicant.yml is ignored and reported.
 */
public final class ResumeComposer {

    private ResumeComposer() {
    }

    public static ComposedResume compose(ResumeProfile profile, EvidenceBank bank) {
        ResumeProfile p = profile == null ? ResumeProfile.empty() : profile;
        List<EvidenceProblem> problems = new ArrayList<>();
        Set<String> printed = new HashSet<>();

        List<ResumeModel.Job> jobs = new ArrayList<>();
        for (ResumeProfile.Employment job : p.experience()) {
            String where = "applicant.yml job '" + job.company() + "'";
            if (!job.bullets().isEmpty()) {
                problems.add(error(where, "still holds " + job.bullets().size()
                        + " resume bullet(s). The evidence bank owns every claim now, so these are "
                        + "ignored; run `evidence --migrate-profile` to remove them"));
            }
            Optional<EvidenceSource> source =
                    bank.sourceNamed(job.company(), EvidenceSource.Kind.EMPLOYMENT);
            List<ResumeModel.Bullet> bullets = List.of();
            if (source.isEmpty()) {
                problems.add(error(where, "has no employment source named '" + job.company()
                        + "' in the evidence bank, so nothing can be printed under it"));
            } else {
                printed.add(source.get().id());
                bullets = bullets(bank, source.get());
            }
            jobs.add(new ResumeModel.Job(job.company(), job.title(), job.location(),
                    job.period(), job.note(), bullets));
        }

        if (!p.projects().isEmpty()) {
            problems.add(error("applicant.yml projects", p.projects().size()
                    + " project(s) are still defined in applicant.yml. Projects, their stack "
                    + "lines and their bullets live in the evidence bank now, so these are "
                    + "ignored; run `evidence --migrate-profile` to remove them"));
        }

        List<ResumeModel.Project> projects = new ArrayList<>();
        for (EvidenceSource source : bank.sources()) {
            if (source.kind() == EvidenceSource.Kind.PROJECT) {
                projects.add(new ResumeModel.Project(source.name(), source.displayStack(),
                        projectTags(bank, source), bullets(bank, source)));
                printed.add(source.id());
            }
        }
        for (EvidenceSource source : bank.sources()) {
            if (source.kind() == EvidenceSource.Kind.EMPLOYMENT && !printed.contains(source.id())) {
                problems.add(warning(source.id(), "no job in applicant.yml carries the title "
                        + "and dates for '" + source.name() + "', so it is not printed"));
            }
        }

        ResumeModel resume = new ResumeModel(p.headline(), p.summaries(), p.skills(), jobs,
                projects, p.education(), p.extras(), p.maxProjects(), p.maxBulletsPerJob(),
                p.maxBulletsPerProject());
        return new ComposedResume(resume, problems);
    }

    /** A source's items as resume bullets: the claim, the item's tags, the evidence id. */
    static List<ResumeModel.Bullet> bullets(EvidenceBank bank, EvidenceSource source) {
        return bank.itemsFrom(source.id()).stream()
                .map(item -> new ResumeModel.Bullet(item.claim(), tags(item), item.id()))
                .toList();
    }

    /** Technologies then concepts, as written, each once. */
    static List<String> tags(EvidenceItem item) {
        Map<String, String> tags = new LinkedHashMap<>();
        item.technologies().forEach(t -> tags.putIfAbsent(t.toLowerCase(Locale.ROOT), t));
        item.concepts().forEach(c -> tags.putIfAbsent(c.toLowerCase(Locale.ROOT), c));
        return List.copyOf(tags.values());
    }

    /** The project's stack, then every item's tags, each once. */
    static List<String> projectTags(EvidenceBank bank, EvidenceSource source) {
        Map<String, String> tags = new LinkedHashMap<>();
        source.stack().forEach(t -> tags.putIfAbsent(t.toLowerCase(Locale.ROOT), t));
        for (EvidenceItem item : bank.itemsFrom(source.id())) {
            tags(item).forEach(t -> tags.putIfAbsent(t.toLowerCase(Locale.ROOT), t));
        }
        return List.copyOf(tags.values());
    }
}
