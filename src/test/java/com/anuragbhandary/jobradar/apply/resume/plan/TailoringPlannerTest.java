package com.anuragbhandary.jobradar.apply.resume.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.rewrite.SkillOrdering;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceFixtures;
import com.anuragbhandary.jobradar.evidence.EvidenceItem;
import com.anuragbhandary.jobradar.evidence.EvidenceProperties;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement extraction → coverage ledger → evidence → plan → verified resume.
 *
 * <p>The ledgers are built from explicit requirements through the real analyzer, so
 * each test says exactly what the posting asked for; one test goes through the
 * deterministic requirement reader from a posting's own text.
 */
class TailoringPlannerTest {

    private static final ResumeModel RESUME = EvidenceFixtures.resume();
    private static final EvidenceBank BANK = EvidenceFixtures.bank();
    private static final CoverageAnalyzer ANALYZER = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(RESUME)), new ResumeSources(RESUME));
    private static final ResumeTailor TAILOR = new ResumeTailor(RESUME);
    private static final TailoringPlanner PLANNER = new TailoringPlanner(RESUME, TAILOR, BANK);
    private static final ResumeRenderer RENDERER = new ResumeRenderer(TestProfiles.indianApplicant());

    private static Requirement req(String term, RequirementImportance importance) {
        return Requirement.of(term, RequirementCategory.TECHNOLOGY, importance, term);
    }

    private static CoverageLedger ledger(Requirement... requirements) {
        return ANALYZER.analyse(1L, "Backend Engineer", "test", List.of(requirements));
    }

    private static Posting posting(String title, String description) {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", title);
        posting.setDescriptionText(description);
        return posting;
    }

    private static final Posting NEUTRAL = posting("Backend Engineer", "Backend work.");

    private static TailoringPlan plan(Requirement... requirements) {
        return PLANNER.plan(NEUTRAL, ledger(requirements));
    }

    private static List<ResumeModel.Bullet> allBullets(TailoredResume resume) {
        List<ResumeModel.Bullet> out = new ArrayList<>();
        resume.experience().forEach(j -> out.addAll(j.bullets()));
        resume.projects().forEach(p -> out.addAll(p.bullets()));
        return out;
    }

    private static List<String> texts(TailoredResume resume) {
        return allBullets(resume).stream().map(ResumeModel.Bullet::text).toList();
    }

    private static final List<Requirement[]> VARIETY = List.of(
            new Requirement[] {req("Kafka", RequirementImportance.REQUIRED)},
            new Requirement[] {req("event-driven", RequirementImportance.REQUIRED),
                    req("deduplication", RequirementImportance.PREFERRED)},
            new Requirement[] {req("Java", RequirementImportance.REQUIRED),
                    req("Spring Boot", RequirementImportance.REQUIRED)},
            new Requirement[] {req("Kubernetes", RequirementImportance.REQUIRED),
                    req("Terraform", RequirementImportance.PREFERRED)},
            new Requirement[] {req("COBOL", RequirementImportance.REQUIRED)},
            new Requirement[] {req("Python", RequirementImportance.REQUIRED),
                    req("asynchronous processing", RequirementImportance.REQUIRED),
                    req("React", RequirementImportance.PREFERRED)});

    // ---- Selection and wording ------------------------------------------------

    @Test
    @DisplayName("an event-driven posting prints the replay evidence in its event-driven wording")
    void eventDrivenPosting() {
        TailoringPlan plan = plan(req("event-driven", RequirementImportance.REQUIRED),
                req("Kafka", RequirementImportance.REQUIRED));

        assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.EVIDENCE);
        assertThat(plan.resume().experience().getFirst().bullets()).first()
                .satisfies(b -> {
                    assertThat(b.text()).isEqualTo(EvidenceFixtures.EVENTS_FIRST);
                    assertThat(b.id()).isEqualTo("acme-replay");
                });
        assertThat(plan.selections()).filteredOn(s -> s.evidenceId().equals("acme-replay"))
                .singleElement().satisfies(s -> {
                    assertThat(s.variantId()).isEqualTo("events-first");
                    assertThat(s.because()).anySatisfy(r -> assertThat(r).startsWith("event-driven:"));
                });
    }

    @Test
    @DisplayName("of two approved wordings, the one answering the weightier requirement wins")
    void multipleApprovedVariants() {
        String eventFirst = plan(req("event-driven", RequirementImportance.REQUIRED),
                req("deduplication", RequirementImportance.PREFERRED)).selections().stream()
                .filter(s -> s.evidenceId().equals("acme-replay")).findFirst().orElseThrow().variantId();
        String dedupFirst = plan(req("deduplication", RequirementImportance.REQUIRED),
                req("event-driven", RequirementImportance.PREFERRED)).selections().stream()
                .filter(s -> s.evidenceId().equals("acme-replay")).findFirst().orElseThrow().variantId();
        String plain = plan(req("Kafka", RequirementImportance.REQUIRED)).selections().stream()
                .filter(s -> s.evidenceId().equals("acme-replay")).findFirst().orElseThrow().variantId();

        assertThat(eventFirst).isEqualTo("events-first");
        assertThat(dedupFirst).isEqualTo("dedup-first");
        assertThat(plain).as("no variant emphasises Kafka alone, so the claim").isEqualTo(EvidenceItem.CLAIM);
    }

    @Test
    @DisplayName("an unapproved variant is never printed, whatever the posting asks for")
    void unapprovedNeverPrinted() {
        for (Requirement[] requirements : VARIETY) {
            assertThat(texts(plan(requirements).resume())).doesNotContain(EvidenceFixtures.UNAPPROVED);
        }
        assertThat(texts(plan(req("Kafka", RequirementImportance.REQUIRED)).resume()))
                .doesNotContain(EvidenceFixtures.UNAPPROVED);
    }

    @Test
    @DisplayName("a Java posting leads with the Java project in its Java wording, and the Java skills")
    void javaPosting() {
        TailoringPlan plan = plan(req("Java", RequirementImportance.REQUIRED),
                req("Spring Boot", RequirementImportance.REQUIRED));

        assertThat(plan.resume().projects()).extracting(ResumeModel.Project::name)
                .containsExactly("Job Scheduler", "Document Pipeline");
        assertThat(plan.resume().projects().getFirst().bullets()).first()
                .extracting(ResumeModel.Bullet::text).isEqualTo(EvidenceFixtures.JAVA_FIRST);
        assertThat(plan.resume().skills().getFirst().group()).isEqualTo("Languages");
        assertThat(plan.resume().skills().getFirst().items().getFirst()).isEqualTo("Java");
        assertThat(plan.resume().droppedProjects()).containsExactly("Static Site");
    }

    @Test
    @DisplayName("an asynchronous Python posting leads with the document pipeline")
    void projectOrderFollowsRelevance() {
        TailoringPlan plan = plan(req("asynchronous processing", RequirementImportance.REQUIRED),
                req("Python", RequirementImportance.REQUIRED));

        assertThat(plan.resume().projects().getFirst().name()).isEqualTo("Document Pipeline");
    }

    @Test
    @DisplayName("bullets are chosen by relevance and printed in the order he wrote them")
    void selectionAndNarrativeOrder() {
        TailoringPlan plan = plan(req("on-call", RequirementImportance.REQUIRED));

        assertThat(texts(plan.resume()).subList(0, 3)).containsExactly(
                EvidenceFixtures.REPLAY, EvidenceFixtures.CACHE, EvidenceFixtures.RUNBOOKS);
        assertThat(plan.selections()).filteredOn(s -> s.evidenceId().equals("acme-ingest"))
                .singleElement().satisfies(s -> assertThat(s.included()).isFalse());
    }

    // ---- Safety -------------------------------------------------------------------

    @Test
    @DisplayName("every printed bullet is an approved wording of evidence from the source it sits under")
    void noUnsupportedEvidence() {
        for (Requirement[] requirements : VARIETY) {
            TailoringPlan plan = plan(requirements);
            assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.EVIDENCE);
            assertThat(ResumeVerifier.verify(plan.resume(), BANK, RESUME)).isEmpty();
            for (ResumeModel.Bullet bullet : allBullets(plan.resume())) {
                EvidenceItem item = BANK.find(bullet.id()).orElseThrow();
                assertThat(item.approvedTexts()).contains(bullet.text());
            }
        }
    }

    @Test
    @DisplayName("nothing is invented: no text outside the bank, no technology outside the items printed")
    void noInventedClaims() {
        Set<String> approved = new HashSet<>();
        BANK.items().forEach(i -> approved.addAll(i.approvedTexts()));
        for (Requirement[] requirements : VARIETY) {
            TailoredResume resume = plan(requirements).resume();
            for (ResumeModel.Bullet bullet : allBullets(resume)) {
                assertThat(approved).contains(bullet.text());
                EvidenceItem item = BANK.find(bullet.id()).orElseThrow();
                Set<String> allowed = new HashSet<>(item.technologyKeys());
                item.contextOnly().forEach(c -> allowed.add(EvidenceItem.key(c)));
                item.concepts().forEach(c -> allowed.add(c.toLowerCase(Locale.ROOT)));
                TechVocabulary.found(item.claim()).forEach(allowed::add);
                assertThat(named(bullet.text())).isSubsetOf(allowed);
            }
            String everything = String.join(" ", texts(resume)).toLowerCase(Locale.ROOT)
                    + resume.skills().toString().toLowerCase(Locale.ROOT);
            assertThat(everything).doesNotContain("kubernetes", "terraform", "cobol");
        }
    }

    /** Technologies the text names, without "spring" counted again inside "spring boot". */
    private static Set<String> named(String text) {
        Set<String> found = new HashSet<>(TechVocabulary.found(text));
        found.removeIf(term -> found.stream().anyMatch(other -> other.startsWith(term + " ")));
        return found;
    }

    @Test
    @DisplayName("every printed item keeps its metrics and qualifiers, whichever wording was chosen")
    void metricsAndQualifiersPreserved() {
        for (Requirement[] requirements : VARIETY) {
            for (ResumeModel.Bullet bullet : allBullets(plan(requirements).resume())) {
                EvidenceItem item = BANK.find(bullet.id()).orElseThrow();
                Stream.concat(item.metrics().stream(), item.qualifiers().stream())
                        .forEach(kept -> assertThat(bullet.text()).containsIgnoringCase(kept));
            }
        }
    }

    @Test
    @DisplayName("shared work is printed with the words saying which part was his")
    void attributionPreserved() {
        TailoringPlan plan = plan(req("WebSockets", RequirementImportance.REQUIRED),
                req("FastAPI", RequirementImportance.REQUIRED));

        assertThat(texts(plan.resume())).contains(EvidenceFixtures.INGEST);
        assertThat(texts(plan.resume())).filteredOn(t -> t.contains("FastAPI"))
                .allSatisfy(t -> assertThat(t).contains("the ingestion side of"));
    }

    // ---- The ledger ----------------------------------------------------------------

    @Test
    @DisplayName("every bullet the ledger cites is evidence in the bank, and every requirement gets a row")
    void ledgerIntegration() {
        CoverageLedger ledger = ledger(req("Kafka", RequirementImportance.REQUIRED),
                req("Kubernetes", RequirementImportance.REQUIRED),
                req("COBOL", RequirementImportance.REQUIRED),
                req("React", RequirementImportance.PREFERRED));
        TailoringPlan plan = PLANNER.plan(NEUTRAL, ledger);

        ledger.entries().forEach(entry -> entry.evidence().stream()
                .filter(ref -> ref.kind() == ResumeSources.Kind.JOB_BULLET
                        || ref.kind() == ResumeSources.Kind.PROJECT_BULLET)
                .forEach(ref -> assertThat(BANK.itemWithText(ref.text())).as(ref.sourceId()).isPresent()));

        assertThat(plan.coverage()).extracting(TailoringPlan.Coverage::requirement)
                .containsExactlyElementsOf(ledger.entries().stream().map(e -> e.requirement().term()).toList());
        assertThat(row(plan, "kafka").evidenceIds()).contains("acme-replay");
        assertThat(row(plan, "kafka").note()).isEqualTo("shown");
        assertThat(row(plan, "kubernetes").evidenceIds()).containsExactly("sched-containers");
        assertThat(row(plan, "kubernetes").note()).contains("is not claimed");
        assertThat(row(plan, "cobol").evidenceIds()).isEmpty();
        assertThat(row(plan, "cobol").note()).isEqualTo("nothing supports this; left out");
        assertThat(row(plan, "react").evidenceIds()).as("React clients are not React work").isEmpty();
        assertThat(plan.requiredGaps()).containsExactly("COBOL");
    }

    private static TailoringPlan.Coverage row(TailoringPlan plan, String term) {
        return plan.coverage().stream().filter(c -> c.requirement().equalsIgnoreCase(term))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("skills are the ledger's order and nothing more: Kubernetes lifts Docker and never appears")
    void skillOrderingRegression() {
        CoverageLedger ledger = ledger(req("Kubernetes", RequirementImportance.REQUIRED));
        TailoringPlan plan = PLANNER.plan(NEUTRAL, ledger);

        assertThat(plan.resume().skills()).isEqualTo(SkillOrdering.reorder(RESUME.skills(), ledger));
        assertThat(plan.resume().skills().getFirst().group()).isEqualTo("Cloud & DevOps");
        assertThat(plan.resume().skills().getFirst().items().getFirst()).isEqualTo("Docker");
        assertThat(ResumeVerifier.sameSkills(RESUME.skills(), plan.resume().skills())).isTrue();
        assertThat(plan.resume().skills()).flatExtracting(ResumeModel.SkillGroup::items)
                .noneMatch(item -> item.toLowerCase(Locale.ROOT).contains("kubernetes"));
    }

    @Test
    @DisplayName("through the requirement reader: a posting's own text plans the event-driven resume")
    void fromThePostingText() {
        Posting posting = posting("Backend Engineer",
                "Requirements:\nExperience building event-driven backend systems with Kafka.");
        TailoringPlan plan = PLANNER.plan(posting, ANALYZER.analyse(posting));

        assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.EVIDENCE);
        assertThat(texts(plan.resume())).contains(EvidenceFixtures.EVENTS_FIRST);
        assertThat(plan.note()).contains("evidence plan:").contains("approved variant");
        assertThat(plan.note().length()).isLessThanOrEqualTo(TailoringPlan.MAX_NOTE);
    }

    // ---- Compatibility, rendering, determinism ---------------------------------------

    @Test
    @DisplayName("for a posting that asks for nothing he has, the rendered resume is exactly the old one")
    void existingResumeCompatibility() {
        Posting mainframe = posting("Mainframe Operator", "COBOL and JCL on z/OS.");
        TailoringPlan plan = PLANNER.plan(mainframe, ANALYZER.analyse(mainframe));
        TailoredResume legacy = TAILOR.tailor(mainframe);

        assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.EVIDENCE);
        assertThat(texts(plan.resume())).isEqualTo(texts(legacy));
        assertThat(RENDERER.toHtml(plan.resume(), RESUME.headline()))
                .isEqualTo(RENDERER.toHtml(legacy, RESUME.headline()));
    }

    @Test
    @DisplayName("the rendered resume carries the chosen wording, and the resume's own headers")
    void renderingRegression() {
        TailoringPlan plan = plan(req("event-driven", RequirementImportance.REQUIRED));
        String html = RENDERER.toHtml(plan.resume(), RESUME.headline());

        assertThat(html).contains(EvidenceFixtures.EVENTS_FIRST)
                .doesNotContain(EvidenceFixtures.REPLAY)
                .contains("Software Engineer · Acme Streaming")
                .contains("Jan 2025 - Dec 2025")
                .contains("contract")
                .contains("Java · Spring Boot · PostgreSQL · Redis · Docker");
    }

    @Test
    @DisplayName("the same posting plans the same resume, every time and in any requirement order")
    void deterministic() {
        TailoringPlan first = plan(req("Kafka", RequirementImportance.REQUIRED),
                req("Java", RequirementImportance.PREFERRED), req("deduplication", RequirementImportance.SIGNAL));
        for (int i = 0; i < 5; i++) {
            TailoringPlan again = plan(req("deduplication", RequirementImportance.SIGNAL),
                    req("Java", RequirementImportance.PREFERRED), req("Kafka", RequirementImportance.REQUIRED));
            assertThat(again.resume()).isEqualTo(first.resume());
            assertThat(again.selections()).isEqualTo(first.selections());
            assertThat(again.explain()).isEqualTo(first.explain());
        }
    }

    // ---- Stepping aside ------------------------------------------------------------

    @Test
    @DisplayName("a bank out of step with the resume is not used, and the resume is the old one exactly")
    void driftFallsBack() {
        EvidenceBank drifted = EvidenceFixtures.bank(EvidenceFixtures.BANK_YAML.replace(
                EvidenceFixtures.RUNBOOKS, "Wrote runbooks."));
        TailoringPlan plan = new TailoringPlanner(RESUME, TAILOR, drifted)
                .plan(NEUTRAL, ledger(req("Kafka", RequirementImportance.REQUIRED)));

        assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.LEGACY);
        assertThat(plan.resume()).isEqualTo(TAILOR.tailor(NEUTRAL));
        assertThat(plan.reasons()).anySatisfy(r -> assertThat(r).contains("does not match the resume"))
                .anySatisfy(r -> assertThat(r).contains("Wrote runbooks for the on-call rotation."));
        assertThat(plan.note()).contains("tailored without the evidence bank");
    }

    @Test
    @DisplayName("no bank at all is the old resume, with the reason")
    void noBank() {
        TailoringPlan plan = new TailoringPlanner(RESUME, TAILOR, EvidenceBank.empty("none", List.of()))
                .plan(NEUTRAL, ledger(req("Kafka", RequirementImportance.REQUIRED)));

        assertThat(plan.mode()).isEqualTo(TailoringPlan.Mode.LEGACY);
        assertThat(plan.resume()).isEqualTo(TAILOR.tailor(NEUTRAL));
        assertThat(plan.reasons()).singleElement().asString().contains("no evidence bank");
    }

    @Test
    @DisplayName("switched off, the pipeline does not even build a ledger")
    void killSwitch() {
        ResumePipeline off = new ResumePipeline(TAILOR, ANALYZER, PLANNER, new EvidenceProperties(null, false));
        ResumePipeline on = new ResumePipeline(TAILOR, ANALYZER, PLANNER, new EvidenceProperties(null, true));

        TailoringPlan legacy = off.tailor(NEUTRAL);

        assertThat(legacy.mode()).isEqualTo(TailoringPlan.Mode.LEGACY);
        assertThat(legacy.ledger()).isNull();
        assertThat(legacy.reasons()).singleElement().asString().contains("switched off");
        assertThat(on.tailor(NEUTRAL).mode()).isEqualTo(TailoringPlan.Mode.EVIDENCE);
    }
}
