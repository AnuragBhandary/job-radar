package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The old {@code extra-answers} list, brought across without losing anything and
 * without inventing a scope for anything.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExtraAnswerMigrationTest {

    @Autowired
    private AssertionRepository assertions;

    private ExtraAnswerMigration migration;

    @BeforeEach
    void setUp() {
        assertions.deleteAll();
        migration = new ExtraAnswerMigration(TestProfiles.indianApplicant(), assertions);
    }

    @Test
    @DisplayName("nothing is lost: every entry becomes an assertion")
    void everyAnswerSurvives() {
        ExtraAnswerMigration.Report report = migration.migrate();

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.migrated()).isEqualTo(3);
        assertThat(assertions.count()).isEqualTo(3);
        // The original wording is kept, which is what makes a wrong migration
        // diagnosable later.
        assertThat(assertions.findAll()).allSatisfy(a ->
                assertThat(a.getSourceQuestion()).isNotBlank());
    }

    @Test
    @DisplayName("a context-sensitive answer never becomes a global rule")
    void contextSensitiveAnswersAreNotGlobalised() {
        migration.migrate();

        Assertion workAuth = assertions.findAll().stream()
                .filter(a -> Concepts.WORK_AUTHORISATION.id().equals(a.getConceptId()))
                .findFirst().orElseThrow();

        // "status that allows you to work" was a global string match for months.
        // It cannot become a global rule now: the country it was written for was
        // never recorded, and guessing would be invisible and wrong.
        assertThat(workAuth.scope().level()).isNotEqualTo(Scope.Level.GLOBAL);
        assertThat(workAuth.scope().isPending()).isTrue();
        assertThat(workAuth.isUsable()).isFalse();
        assertThat(workAuth.isNeedsReview()).isTrue();
        assertThat(workAuth.getNote()).contains("never recorded");
    }

    @Test
    @DisplayName("a pending answer answers nothing until a scope is chosen")
    void pendingAnswersAreInert() {
        migration.migrate();
        Assertion workAuth = assertions.findAll().stream()
                .filter(a -> Concepts.WORK_AUTHORISATION.id().equals(a.getConceptId()))
                .findFirst().orElseThrow();

        assertThat(workAuth.scope().appliesTo(Contexts.indiaOnsite())).isFalse();
        assertThat(workAuth.scope().appliesTo(Contexts.germanyOnsite())).isFalse();
    }

    @Test
    @DisplayName("a genuinely stable answer migrates usable and stays global")
    void stableAnswersKeepWorking() {
        migration.migrate();

        Assertion age = assertions.findAll().stream()
                .filter(a -> Concepts.AGE_OVER_18.id().equals(a.getConceptId()))
                .findFirst().orElseThrow();

        // Whether he is over 18 does not depend on the country, so this one is
        // safe to keep exactly as it behaved before.
        assertThat(age.scope().level()).isEqualTo(Scope.Level.GLOBAL);
        assertThat(age.getValue()).isEqualTo("Yes");
        assertThat(age.isUsable()).isTrue();
        assertThat(age.isNeedsReview()).isFalse();
        assertThat(age.getSource()).isEqualTo(KnowledgeSource.USER_RULE);
    }

    @Test
    @DisplayName("running it twice does not duplicate anything")
    void migrationIsIdempotent() {
        migration.migrate();
        ExtraAnswerMigration.Report second = migration.migrate();

        assertThat(second.skipped()).isEqualTo(3);
        assertThat(second.migrated()).isZero();
        assertThat(assertions.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unclassifiable answer gets its own id rather than colliding")
    void unclassifiableAnswersGetDistinctIds() {
        // Filing them all under one "unrecognised" id would have each supersede
        // the last, which is the one thing a migration must not do.
        assertThat(ExtraAnswerMigration.legacyConceptId("which working setup suits you"))
                .isNotEqualTo(ExtraAnswerMigration.legacyConceptId(
                        "how do you like to work with other engineers"));
        assertThat(ExtraAnswerMigration.legacyConceptId("Do you like tabs / spaces?"))
                .startsWith("legacy.")
                .doesNotContain("/");
    }

    @Test
    @DisplayName("the migration marks what a human still has to decide")
    void reviewQueueIsPopulated() {
        migration.migrate();
        List<Assertion> review = assertions.findByNeedsReviewTrueAndSupersededByIdIsNull();

        assertThat(review).isNotEmpty();
        assertThat(review).allSatisfy(a -> assertThat(a.isUsable()).isFalse());
    }
}
