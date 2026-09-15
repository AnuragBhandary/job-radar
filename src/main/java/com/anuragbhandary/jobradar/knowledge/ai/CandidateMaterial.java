package com.anuragbhandary.jobradar.knowledge.ai;

import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceSource;
import com.anuragbhandary.jobradar.evidence.GroundedProse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a model is allowed to draw on for a career answer, each item under its
 * evidence-bank id.
 *
 * <p>The ids are what make a citation checkable - and, since they are the bank's own
 * stable ids, what makes the answer traceable afterwards: a stored answer citing
 * {@code acme-replay} still resolves to the same evidence after the resume
 * has been re-tailored.
 *
 * <p>Built from evidence-bank items and nothing else. Not the skills list, not the
 * summary, not the profile: a skill he lists is not a demonstrated piece of work, and
 * a model given the skills list will describe using every item on it.
 */
public final class CandidateMaterial {

    private record Entry(EvidenceItem item, EvidenceSource source, String wording) {
    }

    private final Map<String, Entry> items = new LinkedHashMap<>();

    private CandidateMaterial() {
    }

    /**
     * The strongest evidence of one application: what its resume printed, strongest
     * first, so an answer rests on the same evidence as the PDF.
     */
    public static CandidateMaterial of(ApplicationEvidenceContext context, int limit) {
        CandidateMaterial material = new CandidateMaterial();
        List<ApplicationEvidenceContext.EvidenceUse> uses = context.onResume().isEmpty()
                ? context.evidence() : context.onResume();
        uses.stream().limit(limit).forEach(use ->
                material.items.put(use.id(), new Entry(use.item(), use.source(), use.wording())));
        return material;
    }

    /** With no application to hand: the bank's items in file order. */
    public static CandidateMaterial of(EvidenceBank bank, int limit) {
        CandidateMaterial material = new CandidateMaterial();
        bank.items().stream().limit(limit).forEach(item ->
                material.items.put(item.id(), new Entry(item, bank.sourceOf(item), item.claim())));
        return material;
    }

    /** The block that goes into the prompt, one id per item. */
    public String prompt() {
        StringBuilder out = new StringBuilder(
                "HIS EVIDENCE. Every id below may be cited; nothing else about him exists.\n");
        items.forEach((id, entry) -> {
            out.append('[').append(id).append("] ");
            if (entry.source() != null) {
                out.append(entry.source().name()).append(entry.source().kind()
                        == EvidenceSource.Kind.EMPLOYMENT ? " (work): " : " (project): ");
            }
            out.append(entry.wording()).append('\n');
            if (!entry.item().technologies().isEmpty()) {
                out.append("    technologies: ").append(String.join(", ", entry.item().technologies()))
                        .append('\n');
            }
            if (!entry.item().metrics().isEmpty()) {
                out.append("    figures, exactly as written: ")
                        .append(String.join("; ", entry.item().metrics())).append('\n');
            }
            if (!entry.item().qualifiers().isEmpty()) {
                out.append("    keep: \"").append(String.join("\", \"", entry.item().qualifiers()))
                        .append("\"\n");
            }
        });
        return out.toString();
    }

    public boolean hasItem(String ref) {
        return ref != null && items.containsKey(normalise(ref));
    }

    /** The wording shown for an item. */
    public String item(String ref) {
        Entry entry = items.get(normalise(ref));
        return entry == null ? null : entry.wording();
    }

    public Optional<EvidenceItem> evidenceItem(String ref) {
        return Optional.ofNullable(items.get(normalise(ref))).map(Entry::item);
    }

    public Set<String> ids() {
        return Set.copyOf(items.keySet());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** What a draft from this material may rest on. */
    public GroundedProse.Grounding grounding(List<String> allowedNames, List<String> forbidden) {
        List<String> names = new ArrayList<>(allowedNames);
        items.values().forEach(entry -> {
            if (entry.source() != null) {
                names.add(entry.source().name());
            }
        });
        return new GroundedProse.Grounding(items.values().stream().map(Entry::item).toList(),
                names, forbidden);
    }

    private static String normalise(String ref) {
        String trimmed = ref.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
