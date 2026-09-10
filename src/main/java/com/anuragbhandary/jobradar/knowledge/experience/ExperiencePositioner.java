package com.anuragbhandary.jobradar.knowledge.experience;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Decides what may honestly be said about a technology, before anything is written.
 *
 * <p>The service this whole phase exists for. A form asks about Kubernetes; the
 * word is not in his resume; the old behaviour was to stop and ask him. It is a
 * bad behaviour twice over - it interrupts him for something he could answer in
 * his sleep, and the answer he would give ("no, but I've used Docker and built
 * distributed systems") is one the machine already had all the parts for.
 *
 * <h2>Five steps, in order, and it stops at the first that fires</h2>
 * <ol>
 *   <li><b>Direct.</b> His resume names it. Answer yes.</li>
 *   <li><b>Adjacent.</b> His resume names something the graph puts beside it.</li>
 *   <li><b>Conceptual.</b> His resume names something implementing the same idea.</li>
 *   <li><b>Transferable.</b> Neither, but a named engineering foundation applies.</li>
 *   <li><b>None.</b> Nothing. Said plainly.</li>
 * </ol>
 *
 * <p>Every step is a lookup in {@link ExperienceIndex} and {@link SkillGraph}. No
 * model is consulted here and none could be: this is the step that decides what
 * is <em>true</em>, and the model's job starts afterwards, at how to say it.
 *
 * <h2>The invariant</h2>
 * A {@link Positioning} is never stored and never becomes an
 * {@link com.anuragbhandary.jobradar.knowledge.Assertion}. Answering a Kubernetes
 * question does not teach the system that he knows Kubernetes - the index still
 * says he does not, and the next form is positioned from the same evidence rather
 * than from the last answer's prose.
 */
@Service
public class ExperiencePositioner {

    /**
     * How many pieces of evidence an answer may name.
     *
     * <p>Three. An answer listing eight technologies reads as a search of the
     * resume rather than as a person talking, and the fourth-best piece of
     * evidence is never what changes a recruiter's mind.
     */
    private static final int MAX_EVIDENCE = 3;

    private final ExperienceIndex index;

    public ExperiencePositioner(ExperienceIndex index) {
        this.index = index;
    }

    /** What may be said about this technology. Never null, never a guess. */
    public Positioning position(String subject) {
        if (subject == null || subject.isBlank()) {
            return Positioning.none("this");
        }
        String term = SkillGraph.key(subject);

        // 1. He has used it. The only path that licenses a direct claim, and it
        //    is reachable only from a line of his own resume.
        var direct = index.find(term);
        if (direct.isPresent()) {
            return Positioning.direct(subject, direct.get());
        }

        // 2. Beside it. Kubernetes finds Docker; Terraform finds AWS.
        Set<String> neighbours = SkillGraph.neighbours(term);
        List<ExperienceIndex.Entry> nearby = trim(index.matching(neighbours));
        if (!nearby.isEmpty()) {
            return Positioning.adjacent(subject, nearby, named(nearby, neighbours));
        }

        // 3. The idea rather than the tool. Weaker, and it says so.
        Set<String> ideas = SkillGraph.concepts(term);
        List<ExperienceIndex.Entry> viaIdeas = trim(index.matching(ideas));
        if (!viaIdeas.isEmpty()) {
            return Positioning.conceptual(subject, viaIdeas, named(viaIdeas, ideas));
        }
        // A question may name the idea itself - "container orchestration" - in
        // which case the technologies implementing it are the evidence.
        List<ExperienceIndex.Entry> implementers = trim(index.matching(implementersOf(term)));
        if (!implementers.isEmpty()) {
            return Positioning.conceptual(subject, implementers,
                    named(implementers, implementersOf(term)));
        }

        // 4. Nothing technical connects. Only a named foundation will do, and
        //    only if his resume actually carries it - "strong engineering
        //    fundamentals" asserted about nothing is the sentence this whole
        //    class exists to avoid.
        List<ExperienceIndex.Entry> foundation =
                trim(index.matching(new LinkedHashSet<>(SkillGraph.foundations())));
        if (!foundation.isEmpty() && SkillGraph.knows(term)) {
            return Positioning.transferable(subject, foundation,
                    named(foundation, new LinkedHashSet<>(SkillGraph.foundations())));
        }

        // 5. Nothing. Said plainly, with no reaching.
        return Positioning.none(subject);
    }

    /**
     * The terms that actually produced the match, for the explanation.
     *
     * <p>"Adjacent via docker, aws" is checkable; "adjacent" on its own is a
     * verdict he has to take on trust. The list is what {@code Why?} shows.
     */
    private static List<String> named(List<ExperienceIndex.Entry> found, Set<String> through) {
        List<String> via = new ArrayList<>();
        for (ExperienceIndex.Entry entry : found) {
            if (through.contains(entry.term())) {
                via.add(entry.term());
            }
        }
        return via.isEmpty() ? new ArrayList<>(through) : via;
    }

    /**
     * Which technologies implement a named idea.
     *
     * <p>The reverse of {@link SkillGraph#concepts}, walked rather than stored:
     * the map is a couple of hundred entries and a second index of it would be
     * one more thing to keep in step with the first.
     */
    private static Set<String> implementersOf(String idea) {
        Set<String> found = new LinkedHashSet<>();
        for (String technology : SkillGraph.everyTechnology()) {
            if (SkillGraph.concepts(technology).contains(idea)) {
                found.add(technology);
            }
        }
        return found;
    }

    private static List<ExperienceIndex.Entry> trim(List<ExperienceIndex.Entry> entries) {
        return entries.size() <= MAX_EVIDENCE ? entries : entries.subList(0, MAX_EVIDENCE);
    }
}
