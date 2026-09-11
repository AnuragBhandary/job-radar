package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.PostingRequirements;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceLevel;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Puts the skills a posting asks for first, and adds nothing.
 *
 * <p>Deterministic. Only DIRECT requirements count - Kubernetes being ADJACENT to
 * Docker moves nothing, because the skills list is a claim of use. Groups and the
 * items inside them are reordered by the importance of what they answer; ties
 * keep the order he wrote.
 *
 * <p>"AWS (EC2, S3)" arrives from the YAML split into "AWS (EC2" and "S3)", so
 * items inside an open bracket travel together.
 */
public final class SkillOrdering {

    private SkillOrdering() {
    }

    public static List<ResumeModel.SkillGroup> reorder(List<ResumeModel.SkillGroup> skills,
            CoverageLedger ledger) {
        if (skills == null) {
            return List.of();
        }
        Map<String, Double> weights = new HashMap<>();
        for (CoverageLedger.Entry entry : ledger.entries()) {
            if (entry.level() == ExperienceLevel.DIRECT) {
                String key = PostingRequirements.canonicalSubject(entry.requirement().term())
                        .toLowerCase(Locale.ROOT);
                weights.merge(key, entry.requirement().importance().weight(), Math::max);
            }
        }

        record Scored<T>(T value, double score) {
        }
        List<Scored<ResumeModel.SkillGroup>> groups = new ArrayList<>();
        for (ResumeModel.SkillGroup group : skills) {
            List<Scored<List<String>>> units = new ArrayList<>();
            for (List<String> unit : units(group.items() == null ? List.of() : group.items())) {
                units.add(new Scored<>(unit, score(String.join(", ", unit), weights)));
            }
            units.sort(Comparator.comparingDouble((Scored<List<String>> s) -> s.score()).reversed());
            List<String> items = new ArrayList<>();
            units.forEach(u -> items.addAll(u.value()));
            double total = units.stream().mapToDouble(Scored::score).sum();
            groups.add(new Scored<>(new ResumeModel.SkillGroup(group.group(), List.copyOf(items)), total));
        }
        groups.sort(Comparator.comparingDouble((Scored<ResumeModel.SkillGroup> s) -> s.score()).reversed());
        return groups.stream().map(Scored::value).toList();
    }

    static double score(String text, Map<String, Double> weights) {
        Set<String> terms = new HashSet<>();
        terms.add(text.strip().toLowerCase(Locale.ROOT));
        TechVocabulary.found(text).forEach(t -> terms.add(PostingRequirements.ALIASES.getOrDefault(t, t)));
        double best = 0;
        for (String term : terms) {
            best = Math.max(best, weights.getOrDefault(term, 0.0));
        }
        return best;
    }

    /** Items grouped so an open bracket is closed in the same unit. */
    static List<List<String>> units(List<String> items) {
        List<List<String>> units = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int depth = 0;
        for (String item : items) {
            current.add(item);
            for (char c : item.toCharArray()) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                }
            }
            if (depth == 0) {
                units.add(List.copyOf(current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            units.add(List.copyOf(current));
        }
        return units;
    }
}
