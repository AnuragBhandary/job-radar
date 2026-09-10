package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Giving migrated knowledge the scope nobody chose for it.
 *
 * <p>Ten answers came across from {@code extra-answers} switched off, four of
 * them about sponsorship and work authorisation. These tests are about what
 * happens when he switches one on: it must land where he says, it must not be
 * allowed to land everywhere, and what it used to be must survive.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeReviewTest {

    @Autowired
    private AssertionRepository assertions;

    private KnowledgeService knowledge;
    private ExtraAnswerMigration migration;

    @BeforeEach
    void setUp() {
        assertions.deleteAll();
        knowledge = new KnowledgeService(assertions, new SessionAnswers());
        migration = new ExtraAnswerMigration(TestProfiles.indianApplicant(), assertions);
        migration.migrate();
    }

    private Assertion pendingWorkAuth() {
        return assertions.findByNeedsReviewTrueAndSupersededByIdIsNull().stream()
                .filter(a -> Concepts.WORK_AUTHORISATION.id().equals(a.getConceptId()))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("choosing a country makes a migrated answer usable, for that country only")
    void rescopingActivatesOneCountry() {
        Assertion pending = pendingWorkAuth();
        assertThat(pending.isUsable()).isFalse();

        Assertion scoped = knowledge.rescope(pending.getId(), Scope.country("NL"),
                "knowledge review");

        assertThat(scoped.isUsable()).isTrue();
        assertThat(scoped.scope()).isEqualTo(Scope.country("NL"));
        assertThat(scoped.getValue()).isEqualTo(pending.getValue());
        assertThat(scoped.scope().appliesTo(Contexts.germanyOnsite())).isFalse();
        assertThat(scoped.scope().appliesTo(Contexts.indiaOnsite())).isFalse();
    }

    @Test
    @DisplayName("the version that arrived without a scope survives the decision")
    void rescopingSupersedesRatherThanEdits() {
        Assertion pending = pendingWorkAuth();
        String originalWording = pending.getSourceQuestion();

        Assertion scoped = knowledge.rescope(pending.getId(), Scope.country("NL"), "review");

        Assertion before = assertions.findById(pending.getId()).orElseThrow();
        assertThat(before.isLive()).isFalse();
        assertThat(before.getSupersededById()).isEqualTo(scoped.getId());
        assertThat(before.isNeedsReview()).isFalse();
        // The new row still knows where it came from.
        assertThat(scoped.getSourceQuestion()).isEqualTo(originalWording);
        assertThat(scoped.getNote()).contains("was ");
    }

    @Test
    @DisplayName("the server refuses a global scope for a context-sensitive concept")
    void serverRejectsGlobalScope() {
        // The UI is not the boundary. A client that sends GLOBAL - by bug, by
        // curl, by an old bookmark - is refused rather than quietly narrowed,
        // because quietly narrowing hides whatever sent it.
        Assertion pending = pendingWorkAuth();

        assertThatThrownBy(() -> knowledge.rescope(pending.getId(), Scope.global(), "review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-sensitive");

        assertThat(assertions.findById(pending.getId()).orElseThrow().isLive()).isTrue();
        assertThat(assertions.findById(pending.getId()).orElseThrow().isUsable()).isFalse();
    }

    @Test
    @DisplayName("a scope with no value is refused - it would answer nothing")
    void serverRejectsPendingScope() {
        Assertion pending = pendingWorkAuth();
        assertThatThrownBy(() -> knowledge.rescope(pending.getId(),
                Scope.of(Scope.Level.COUNTRY, null), "review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a value");
    }

    @Test
    @DisplayName("a strategic-class scope covers a lane rather than a country")
    void rescopingToALane() {
        // "For anything that means moving abroad" - the second of the two safe
        // answers the review page offers for a migrated sponsorship rule.
        Assertion pending = pendingWorkAuth();
        Assertion scoped = knowledge.rescope(pending.getId(),
                Scope.of(Scope.Level.STRATEGIC_CLASS, "INTERNATIONAL_RELOCATION"), "review");

        assertThat(scoped.scope().appliesTo(Contexts.germanyOnsite())).isTrue();
        assertThat(scoped.scope().appliesTo(Contexts.usOnsite())).isTrue();
        assertThat(scoped.scope().appliesTo(Contexts.usRemoteFromIndia())).isFalse();
        assertThat(scoped.scope().appliesTo(Contexts.indiaOnsite())).isFalse();
    }

    @Test
    @DisplayName("editing a value keeps what it used to say")
    void editingPreservesHistory() {
        Assertion pending = pendingWorkAuth();
        String was = pending.getValue();

        Assertion edited = knowledge.edit(pending.getId(), "Yes, I hold an EU permit",
                Scope.country("NL"), "review");

        assertThat(edited.getValue()).isEqualTo("Yes, I hold an EU permit");
        assertThat(edited.getNote()).contains(was);
        assertThat(assertions.findById(pending.getId()).orElseThrow().getValue())
                .isEqualTo(was);
    }

    @Test
    @DisplayName("discarding keeps the row and stops it being used")
    void discardingIsNotDeleting() {
        Assertion pending = pendingWorkAuth();
        knowledge.discard(pending.getId(), "review");

        Assertion after = assertions.findById(pending.getId()).orElseThrow();
        assertThat(after.getApproval()).isEqualTo(ApprovalState.REJECTED);
        assertThat(after.isUsable()).isFalse();
        assertThat(after.isNeedsReview()).isFalse();
    }

    @Test
    @DisplayName("keeping it pending removes it from the queue without activating it")
    void keepingPending() {
        Assertion pending = pendingWorkAuth();
        knowledge.keepPending(pending.getId());

        Assertion after = assertions.findById(pending.getId()).orElseThrow();
        assertThat(after.isNeedsReview()).isFalse();
        assertThat(after.isUsable()).isFalse();
        assertThat(after.scope().isPending()).isTrue();
    }

    @Test
    @DisplayName("an unclassifiable legacy answer cannot be given a scope yet")
    void legacyAnswersHaveNoConcept() {
        // They are kept and readable; giving one a scope would mean deciding
        // which concept it is, and that is a different decision.
        Assertion legacy = assertions.findAll().stream()
                .filter(a -> a.getConceptId().startsWith("legacy."))
                .findFirst().orElse(null);
        if (legacy == null) {
            return;
        }
        assertThatThrownBy(() -> knowledge.rescope(legacy.getId(), Scope.global(), "review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a known concept");
    }
}
