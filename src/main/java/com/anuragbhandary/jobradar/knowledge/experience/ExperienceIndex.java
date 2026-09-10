package com.anuragbhandary.jobradar.knowledge.experience;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Everything the applicant has actually worked with, read out of his own resume.
 *
 * <p>Built once at startup from {@link ResumeModel} and nothing else. No model
 * call, no inference, no "probably knows": a technology is in this index because
 * a line of his resume names it, and the line is kept so the answer can cite it.
 *
 * <h2>One entry per technology, several pieces of evidence</h2>
 * "Java" appears in the skills list, in three project stacks and in a dozen
 * bullets. That is one thing he knows with twelve pieces of evidence, not twelve
 * things - so entries are merged on the normalised term and accumulate evidence.
 * The alternative produces three unrelated Java records and a knowledge page that
 * looks like a bug.
 *
 * <h2>Where depth comes from</h2>
 * Professional work outranks a personal project, and both outrank a bare mention
 * in a skills list, because that is the order an interviewer would weight them
 * in. It is recorded rather than computed into a score: "used at work" and "used
 * in a project" are different sentences in an answer, and a number cannot be
 * turned back into either.
 */
@Component
public class ExperienceIndex {

    private final Map<String, Entry> byTerm = new LinkedHashMap<>();

    public ExperienceIndex(ResumeModel resume) {
        if (resume == null) {
            return;
        }
        readSkills(resume);
        readExperience(resume);
        readProjects(resume);
    }

    /**
     * One technology, and every place his resume names it.
     *
     * @param term     the normalised name, which is what the graph is keyed on
     * @param display  how his resume writes it, which is what an answer should say
     * @param evidence every mention, strongest first
     */
    public record Entry(String term, String display, List<ExperienceEvidence> evidence) {

        /** The strongest place it appears. Decides how an answer describes it. */
        public ExperienceEvidence.Depth depth() {
            return evidence.stream()
                    .map(ExperienceEvidence::depth)
                    .min(java.util.Comparator.comparingInt(Enum::ordinal))
                    .orElse(ExperienceEvidence.Depth.LISTED);
        }

        /** True when a job he held names it, rather than only a project. */
        public boolean isProfessional() {
            return depth() == ExperienceEvidence.Depth.PROFESSIONAL;
        }
    }

    // ------------------------------------------------------------------
    // Reading the resume
    // ------------------------------------------------------------------

    private void readSkills(ResumeModel resume) {
        if (resume.skills() == null) {
            return;
        }
        for (ResumeModel.SkillGroup group : resume.skills()) {
            if (group.items() == null) {
                continue;
            }
            for (String item : group.items()) {
                // The item verbatim, but only when it is one clean name.
                // "AWS (EC2, S3)" is a skill written with its services in
                // brackets, and indexing it whole produced the terms "aws (ec2"
                // and "s3)" - which match nothing and read as a parser bug on
                // the audit page.
                if (isPlainTerm(item)) {
                    record(item, item, ExperienceEvidence.Source.RESUME_SKILLS,
                            group.group(), ExperienceEvidence.Depth.LISTED);
                }
                // Then whatever the vocabulary finds inside it, which is how the
                // bracketed services get indexed properly.
                for (String term : TechVocabulary.found(item)) {
                    // The vocabulary's own spelling when the entry is messy. His
                    // skills list has "AWS (EC2, S3)" written as a YAML flow
                    // sequence, so it arrives already split into "AWS (EC2" and
                    // "S3)" - and an answer citing "AWS (EC2" reads as a bug.
                    record(term, isPlainTerm(item) ? item : term,
                            ExperienceEvidence.Source.RESUME_SKILLS,
                            group.group(), ExperienceEvidence.Depth.LISTED);
                }
            }
        }
    }

    private void readExperience(ResumeModel resume) {
        if (resume.experience() == null) {
            return;
        }
        for (ResumeModel.Job job : resume.experience()) {
            String where = job.company() == null ? "a previous role" : job.company();
            if (job.bullets() == null) {
                continue;
            }
            for (ResumeModel.Bullet bullet : job.bullets()) {
                for (String term : TechVocabulary.found(bullet.text())) {
                    record(term, term, ExperienceEvidence.Source.RESUME_EXPERIENCE,
                            where, ExperienceEvidence.Depth.PROFESSIONAL);
                }
                bullet.tags().forEach(tag ->
                        record(tag, tag, ExperienceEvidence.Source.RESUME_EXPERIENCE,
                                where, ExperienceEvidence.Depth.PROFESSIONAL));
            }
        }
    }

    private void readProjects(ResumeModel resume) {
        for (ResumeModel.Project project : resume.projects()) {
            String where = project.name() == null ? "a project" : project.name();
            // The stack line is the densest evidence on a resume: "Java · Spring
            // Boot · PostgreSQL · Redis · Kafka · JUnit · Maven · Docker".
            for (String term : TechVocabulary.found(project.stack())) {
                record(term, term, ExperienceEvidence.Source.PROJECT_STACK, where,
                        ExperienceEvidence.Depth.PROJECT);
            }
            project.tags().forEach(tag ->
                    record(tag, tag, ExperienceEvidence.Source.PROJECT_TAGS, where,
                            ExperienceEvidence.Depth.PROJECT));
            if (project.bullets() != null) {
                for (ResumeModel.Bullet bullet : project.bullets()) {
                    for (String term : TechVocabulary.found(bullet.text())) {
                        record(term, term, ExperienceEvidence.Source.PROJECT_BULLET,
                                where, ExperienceEvidence.Depth.PROJECT);
                    }
                }
            }
        }
    }

    /**
     * Adds one mention, merging into whatever is already known about the term.
     *
     * <p>The display name is only taken from the first mention. His skills list
     * writes "Apache Kafka" and a project stack writes "Kafka"; an answer should
     * use one of them consistently rather than whichever the last loop happened
     * to see.
     */
    private void record(String rawTerm, String display, ExperienceEvidence.Source source,
            String where, ExperienceEvidence.Depth depth) {

        String term = SkillGraph.key(rawTerm);
        if (term.isBlank() || term.length() > 60) {
            return;
        }
        Entry existing = byTerm.get(term);
        if (existing == null) {
            List<ExperienceEvidence> evidence = new ArrayList<>();
            evidence.add(new ExperienceEvidence(display, source, where, depth));
            byTerm.put(term, new Entry(term, display, evidence));
            return;
        }
        // One mention per place, not per source. A project names Docker in its
        // stack line and again in a bullet; that is one project, and listing it
        // twice made the Kubernetes positioning cite Docker seven times.
        boolean seen = existing.evidence().stream()
                .anyMatch(item -> item.depth() == depth
                        && java.util.Objects.equals(item.where(), where));
        if (!seen) {
            existing.evidence().add(new ExperienceEvidence(display, source, where, depth));
        }
    }

    // ------------------------------------------------------------------
    // Reading the index
    // ------------------------------------------------------------------

    /** What he knows about one technology, if anything. */
    public Optional<Entry> find(String technology) {
        return Optional.ofNullable(byTerm.get(SkillGraph.key(technology)));
    }

    public boolean has(String technology) {
        return byTerm.containsKey(SkillGraph.key(technology));
    }

    /** Every technology the resume names, for the audit and for tests. */
    public Set<String> terms() {
        return new LinkedHashSet<>(byTerm.keySet());
    }

    public int size() {
        return byTerm.size();
    }

    /**
     * The entries matching any of a set of terms, strongest evidence first.
     *
     * <p>Ordered so an answer names his best evidence first: work before
     * projects, projects before a skills-list mention. A sentence that opens with
     * the weakest thing he has is a sentence that reads as an excuse.
     */
    public List<Entry> matching(Set<String> terms) {
        List<Entry> found = new ArrayList<>();
        for (String term : terms) {
            find(term).ifPresent(entry -> {
                if (!found.contains(entry)) {
                    found.add(entry);
                }
            });
        }
        found.sort(java.util.Comparator
                .comparingInt((Entry entry) -> entry.depth().ordinal())
                .thenComparing(Entry::term));
        return List.copyOf(found);
    }

    /**
     * Whether a skills-list entry is a single name rather than a phrase.
     *
     * <p>"Spring Boot" and "PostgreSQL" index cleanly; "AWS (EC2, S3)" and
     * "Backend & APIs" do not, and the vocabulary pass picks up what is inside
     * them anyway.
     */
    private static boolean isPlainTerm(String item) {
        return item != null && !item.isBlank() && item.length() <= 40
                && item.indexOf('(') < 0 && item.indexOf(')') < 0
                && item.indexOf(',') < 0 && item.indexOf('&') < 0
                && item.indexOf('/') < 0;
    }

    /** Lowercased, for callers that have their own strings to compare. */
    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
