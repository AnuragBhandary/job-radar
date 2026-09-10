package com.anuragbhandary.jobradar.knowledge.ai;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a model is allowed to draw on, with an id against each item.
 *
 * <p>The ids are what make a citation checkable. Given "R3" the model has to mean
 * a specific resume bullet, and {@link AnswerProposer} can confirm that R3 exists
 * and that the sentence it produced is about something R3 mentions. A model asked
 * only for prose can cite nothing and so can be checked for nothing.
 *
 * <p>Built from {@link ResumeModel} alone - the same constraint the resume tailor
 * and the cover-letter writer already work under, and for the same reason: every
 * line in that file is something its author can defend in an interview, and
 * nothing else has ever been true of anything else.
 */
public final class CandidateMaterial {

    private final Map<String, String> items = new LinkedHashMap<>();
    private final Set<String> vocabulary;

    private CandidateMaterial(ResumeModel resume) {
        StringBuilder everything = new StringBuilder();
        int index = 1;

        if (resume.experience() != null) {
            for (ResumeModel.Job job : resume.experience()) {
                for (ResumeModel.Bullet bullet : job.bullets()) {
                    items.put("R" + index++,
                            job.title() + " at " + job.company() + ": " + bullet.text());
                    everything.append(bullet.text()).append(' ');
                }
            }
        }
        int project = 1;
        for (ResumeModel.Project item : resume.projects()) {
            StringBuilder text = new StringBuilder(item.name() + " [" + item.stack() + "]");
            for (ResumeModel.Bullet bullet : item.bullets()) {
                text.append("; ").append(bullet.text());
                everything.append(bullet.text()).append(' ');
            }
            items.put("P" + project++, text.toString());
            everything.append(item.stack()).append(' ');
        }
        int degree = 1;
        if (resume.education() != null) {
            for (ResumeModel.Education education : resume.education()) {
                items.put("E" + degree++,
                        education.degree() + ", " + education.institution()
                                + " (" + education.period() + ")");
            }
        }
        int skill = 1;
        for (ResumeModel.SkillGroup group : resume.skills()) {
            items.put("S" + skill++, group.group() + ": " + String.join(", ", group.items()));
            everything.append(String.join(" ", group.items())).append(' ');
        }
        resume.allTags().forEach(tag -> everything.append(tag).append(' '));

        this.vocabulary = new LinkedHashSet<>(TechVocabulary.found(everything.toString()));
    }

    public static CandidateMaterial of(ResumeModel resume) {
        return new CandidateMaterial(resume);
    }

    /** The block that goes into the prompt, one id per line. */
    public String prompt() {
        StringBuilder out = new StringBuilder(
                "HIS MATERIAL. Every id below may be cited; nothing else exists.\n");
        items.forEach((id, text) -> out.append(id).append(": ").append(text).append('\n'));
        return out.toString();
    }

    public boolean hasItem(String ref) {
        return ref != null && items.containsKey(ref.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public String item(String ref) {
        return items.get(ref.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public Set<String> ids() {
        return Set.copyOf(items.keySet());
    }

    /** Every technology the resume actually names, normalised. */
    public Set<String> vocabulary() {
        return Set.copyOf(vocabulary);
    }

    /**
     * Technologies a draft names that the resume does not.
     *
     * <p>The check the phrase blocklists could never do. Both sides go through
     * {@link TechVocabulary} so "PostgreSQL" and "Postgres" are one skill, and a
     * draft claiming Kubernetes when the resume has never mentioned it comes back
     * with exactly that word in the list.
     */
    public List<String> unsupportedTechnologies(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> unsupported = new ArrayList<>();
        for (String term : TechVocabulary.found(text)) {
            if (!vocabulary.contains(term)) {
                unsupported.add(term);
            }
        }
        return List.copyOf(unsupported);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
