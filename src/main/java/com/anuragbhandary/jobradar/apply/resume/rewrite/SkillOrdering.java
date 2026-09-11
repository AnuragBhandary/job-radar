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
 * <p>Deterministic, and never the model's doing. The skills list is a claim of
 * use, so it only ever holds what he wrote; this changes the order and nothing
 * else.
 *
 * <h2>What moves a skill</h2>
 * <ul>
 *   <li>A DIRECT requirement: the skill carrying it, at the requirement's full
 *       weight.</li>
 *   <li>An ADJACENT, CONCEPTUAL or TRANSFERABLE requirement: the skills behind the
 *       verdict - Docker for Kubernetes, AWS for Terraform - at the weight times
 *       the level's credit, so emphasis follows the honest evidence. Kubernetes
 *       itself is never added: he has not used it.</li>
 *   <li>NONE: nothing.</li>
 * </ul>
 * Groups and the items inside them are ordered by that weight; ties keep the
 * order he wrote. "AWS (EC2, S3)" arrives from the YAML split into "AWS (EC2" and
 * "S3)", so items inside an open bracket travel together.
 */
public final class SkillOrdering {

    private SkillOrdering() {
    }

    public static List<ResumeModel.SkillGroup> reorder(List<ResumeModel.SkillGroup> skills,
            CoverageLedger ledger) {
        if (skills == null) {
            return List.of();
        }
        Map<String, Double> weights = weights(ledger);

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

    static Map<String, Double> weights(CoverageLedger ledger) {
        Map<String, Double> weights = new HashMap<>();
        for (CoverageLedger.Entry entry : ledger.entries()) {
            double weight = entry.requirement().importance().weight();
            if (entry.level() == ExperienceLevel.DIRECT) {
                String key = PostingRequirements.canonicalSubject(entry.requirement().term())
                        .toLowerCase(Locale.ROOT);
                weights.merge(key, weight, Math::max);
            } else if (entry.level() != ExperienceLevel.NONE) {
                double emphasis = weight * CoverageLedger.credit(entry.level());
                for (String via : entry.via()) {
                    weights.merge(via.toLowerCase(Locale.ROOT), emphasis, Math::max);
                }
            }
        }
        return weights;
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
