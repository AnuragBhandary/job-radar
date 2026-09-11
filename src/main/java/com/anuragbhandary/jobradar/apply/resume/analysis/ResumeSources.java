package com.anuragbhandary.jobradar.apply.resume.analysis;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every piece of the resume a ledger may cite, each with an id that does not move.
 *
 * <p>The id is the hinge for the next phase. A model that rewrites a bullet will
 * be required to say which bullet it rewrote, and Java will look that id up here
 * and check the rewrite against the original. So the id has to name one bullet,
 * always the same one, however many times the resume is tailored.
 *
 * <h2>Where an id comes from</h2>
 * <ol>
 *   <li>An {@code id:} written on the bullet in applicant.yml, when there is one.
 *       Survives every edit, because a person chose it.</li>
 *   <li>Otherwise one derived from the bullet: its parent (company or project)
 *       and a hash of its own text. Survives reordering, re-tailoring and edits
 *       to <em>other</em> bullets; changes when this bullet's wording changes -
 *       which is right, because a rewrite keyed to the old sentence should not
 *       silently attach to a new one.</li>
 * </ol>
 * Positions are never used. Inserting a bullet at the top of a job would renumber
 * everything under it, and a rewrite would land on its neighbour.
 *
 * <p>Nothing here is sent to a model and nothing here changes what is rendered.
 * It reads the same {@link ResumeModel} the tailor and the index read.
 */
@Component
public class ResumeSources {

    /** Declared in citation order: work first, then projects, then the skills list. */
    public enum Kind {
        JOB_BULLET,
        PROJECT_BULLET,
        PROJECT_STACK,
        SKILL_GROUP
    }

    /**
     * @param parent the company, project or skills group it belongs to
     * @param terms  normalised technology names it carries, read the same way
     *               {@link com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex}
     *               reads them, so the index's terms can be traced to items here
     */
    public record SourceItem(String id, Kind kind, String parent, String text, Set<String> terms) {

        public boolean isBullet() {
            return kind == Kind.JOB_BULLET || kind == Kind.PROJECT_BULLET;
        }
    }

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Map<String, SourceItem> byId = new LinkedHashMap<>();
    private final Map<ResumeModel.Bullet, String> bulletIds = new IdentityHashMap<>();

    public ResumeSources(ResumeModel resume) {
        if (resume == null) {
            return;
        }
        Set<String> reserved = explicitIds(resume);

        if (resume.experience() != null) {
            for (ResumeModel.Job job : resume.experience()) {
                String parent = job.company() == null ? "job" : job.company();
                for (ResumeModel.Bullet bullet : orEmpty(job.bullets())) {
                    addBullet(bullet, Kind.JOB_BULLET, "job-" + slug(parent), parent, reserved);
                }
            }
        }
        for (ResumeModel.Project project : resume.projects()) {
            String parent = project.name() == null ? "project" : project.name();
            String prefix = "proj-" + slug(parent);
            if (project.stack() != null && !project.stack().isBlank()) {
                Set<String> terms = termsOf(project.stack());
                project.tags().forEach(tag -> terms.add(key(tag)));
                put(new SourceItem(unique(prefix + "-stack", reserved), Kind.PROJECT_STACK,
                        parent, project.stack(), terms));
            }
            for (ResumeModel.Bullet bullet : orEmpty(project.bullets())) {
                addBullet(bullet, Kind.PROJECT_BULLET, prefix, parent, reserved);
            }
        }
        if (resume.skills() != null) {
            for (ResumeModel.SkillGroup group : resume.skills()) {
                List<String> items = orEmpty(group.items());
                Set<String> terms = new LinkedHashSet<>();
                for (String item : items) {
                    if (isPlainTerm(item)) {
                        terms.add(key(item));
                    }
                    terms.addAll(termsOf(item));
                }
                String name = group.group() == null ? "skills" : group.group();
                put(new SourceItem(unique("skills-" + slug(name), reserved), Kind.SKILL_GROUP,
                        name, String.join(", ", items), terms));
            }
        }
    }

    public List<SourceItem> all() {
        return List.copyOf(byId.values());
    }

    public Optional<SourceItem> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    /**
     * The id of this bullet instance.
     *
     * <p>By identity, not by value. The tailor passes the bound bullets through
     * unchanged, so a bullet taken from a {@code TailoredResume} resolves to the
     * same id as the one in the source - which is the property a rewrite needs,
     * and which a value lookup would lose for two identical sentences.
     */
    public Optional<String> idOf(ResumeModel.Bullet bullet) {
        return Optional.ofNullable(bulletIds.get(bullet));
    }

    /** Items carrying this normalised term, strongest kind first. */
    public List<SourceItem> itemsNaming(String term) {
        String wanted = key(term);
        return byId.values().stream()
                .filter(item -> item.terms().contains(wanted))
                .sorted(Comparator.comparingInt(item -> item.kind().ordinal()))
                .toList();
    }

    public List<SourceItem> bullets() {
        return byId.values().stream().filter(SourceItem::isBullet).toList();
    }

    // ------------------------------------------------------------------

    private void addBullet(ResumeModel.Bullet bullet, Kind kind, String prefix, String parent,
            Set<String> reserved) {
        if (bullet == null || bullet.text() == null) {
            return;
        }
        String id = bullet.id() != null && !bullet.id().isBlank()
                ? bullet.id().strip()
                : unique(prefix + "-" + hash8(bullet.text()), reserved);
        Set<String> terms = termsOf(bullet.text());
        bullet.tags().forEach(tag -> terms.add(key(tag)));
        put(new SourceItem(id, kind, parent, bullet.text(), terms));
        bulletIds.put(bullet, id);
    }

    private void put(SourceItem item) {
        byId.put(item.id(), item);
    }

    /**
     * A derived id that no written id and no earlier item has taken.
     *
     * <p>Two identical sentences in one job would otherwise share an id; the
     * second gets a suffix, and keeps it for as long as the file is unchanged.
     */
    private String unique(String candidate, Set<String> reserved) {
        String id = candidate;
        int n = 2;
        while (byId.containsKey(id) || reserved.contains(id)) {
            id = candidate + "-" + n++;
        }
        return id;
    }

    /**
     * Collects the written ids, refusing duplicates and malformed ones.
     *
     * <p>Loudly, at startup. Two bullets sharing an id would let a rewrite of one
     * be checked against the other, which is the exact failure the id exists to
     * prevent.
     */
    private static Set<String> explicitIds(ResumeModel resume) {
        List<ResumeModel.Bullet> all = new ArrayList<>();
        if (resume.experience() != null) {
            resume.experience().forEach(job -> all.addAll(orEmpty(job.bullets())));
        }
        resume.projects().forEach(project -> all.addAll(orEmpty(project.bullets())));

        Set<String> ids = new HashSet<>();
        for (ResumeModel.Bullet bullet : all) {
            if (bullet == null || bullet.id() == null || bullet.id().isBlank()) {
                continue;
            }
            String id = bullet.id().strip();
            if (!VALID_ID.matcher(id).matches()) {
                throw new IllegalStateException("Resume bullet id '" + id
                        + "' must be letters, digits, '.', '_' or '-', at most 64 characters");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("Resume bullet id '" + id
                        + "' is used twice in job-radar.resume - every id must name one bullet");
            }
        }
        return ids;
    }

    private static Set<String> termsOf(String text) {
        Set<String> terms = new LinkedHashSet<>();
        TechVocabulary.found(text).forEach(term -> terms.add(key(term)));
        return terms;
    }

    /** The same rule {@code ExperienceIndex} uses for a skills-list entry. */
    private static boolean isPlainTerm(String item) {
        return item != null && !item.isBlank() && item.length() <= 40
                && item.indexOf('(') < 0 && item.indexOf(')') < 0
                && item.indexOf(',') < 0 && item.indexOf('&') < 0
                && item.indexOf('/') < 0;
    }

    static String hash8(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(QuoteGrounding.normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    static String slug(String value) {
        String slug = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "x" : slug;
    }

    /** Must agree with {@code SkillGraph.key}, which is what index terms are keyed on. */
    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
