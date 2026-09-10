package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.form.FieldKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConceptsTest {

    @Test
    @DisplayName("every field kind the classifier can produce has a concept")
    void everyKindIsMapped() {
        // UNKNOWN is the one exception and is deliberate: it means the classifier
        // declined to place the field, and the caller falls back to aliases.
        for (FieldKind kind : FieldKind.values()) {
            if (kind == FieldKind.UNKNOWN) {
                continue;
            }
            assertThat(Concepts.forKind(kind))
                    .as("no concept for %s", kind)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("concept ids are unique and stable")
    void idsAreUnique() {
        assertThat(Concepts.all()).extracting(Concept::id).doesNotHaveDuplicates();
        // Ids are what assertions are stored against, so they are literals here
        // rather than anything derived from a label that might be reworded.
        assertThat(Concepts.SPONSORSHIP_REQUIRED.id()).isEqualTo("sponsorship.required");
        assertThat(Concepts.WORK_AUTHORISATION.id()).isEqualTo("work_authorization");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Will you now or in the future require sponsorship?   | sponsorship.required",
            "Do you require sponsorship?                          | sponsorship.required",
            "Do you require employer sponsorship?                 | sponsorship.required",
            "Are you legally authorized to work in Germany?       | work_authorization",
            "Do you have the right to work in Ireland?            | work_authorization",
            "Are you at least 18 years of age?                    | age_over_18",
            "Do you have a valid passport?                        | valid_passport",
            "What is your highest level of education?             | education_degree",
            "Why do you want to work at Camunda?                  | why_company",
            "Are you related to any current employees?            | related_to_employee",
            "How did you hear about us?                           | how_did_you_hear",
    })
    @DisplayName("real question phrasings reach the same concept")
    void aliasesResolve(String question, String expectedId) {
        // Several of these are worded differently on every board. The whole point
        // of a concept is that they are one piece of knowledge, not six.
        Concept resolved = Concepts.byAlias(question).orElse(Concept.UNRECOGNISED);
        assertThat(resolved.id()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("the longest matching alias wins")
    void longestAliasWins() {
        // "legally eligible to work in the country" and shorter overlapping
        // phrases both match; the specific one has to win rather than whichever
        // concept happened to be declared first.
        assertThat(Concepts.byAlias(
                "Are you legally eligible to work in the country where you plan to work from?")
                .orElseThrow().id()).isEqualTo("work_authorization");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sponsorship.required", "work_authorization", "willing_to_relocate",
            "salary_expectation", "remote_eligibility",
    })
    @DisplayName("a context-sensitive concept cannot be stored globally")
    void contextSensitiveConceptsForbidGlobal(String id) {
        // The enforcement of the rule this whole phase exists for. It is not that
        // the UI declines to offer global - there is no scope to store it at.
        Concept concept = Concepts.byId(id).orElseThrow();
        assertThat(concept.contextSensitive()).isTrue();
        assertThat(concept.allowsScope(Scope.Level.GLOBAL)).isFalse();
        assertThat(concept.defaultScope()).isNotEqualTo(Scope.Level.GLOBAL);
    }

    @Test
    @DisplayName("a stable fact may be global, and defaults to it")
    void stableFactsAllowGlobal() {
        assertThat(Concepts.AGE_OVER_18.allowsScope(Scope.Level.GLOBAL)).isTrue();
        assertThat(Concepts.AGE_OVER_18.defaultScope()).isEqualTo(Scope.Level.GLOBAL);
        assertThat(Concepts.AGE_OVER_18.contextSensitive()).isFalse();
    }

    @Test
    @DisplayName("a per-employer question is never global either")
    void companyQuestionsStayWithTheirCompany() {
        assertThat(Concepts.WHY_COMPANY.allowsScope(Scope.Level.GLOBAL)).isFalse();
        assertThat(Concepts.WHY_COMPANY.defaultScope()).isEqualTo(Scope.Level.COMPANY);
        assertThat(Concepts.WHY_ROLE.defaultScope()).isEqualTo(Scope.Level.APPLICATION);
    }

    @Test
    @DisplayName("only open-ended prose is open to a model")
    void aiEligibilityIsNarrow() {
        // A model has no way to know his notice period and every reason to sound
        // like it does.
        assertThat(Concepts.WHY_COMPANY.aiEligible()).isTrue();
        assertThat(Concepts.WHY_ROLE.aiEligible()).isTrue();
        assertThat(Concepts.COVER_LETTER_TEXT.aiEligible()).isTrue();

        assertThat(Concepts.SPONSORSHIP_REQUIRED.aiEligible()).isFalse();
        assertThat(Concepts.NOTICE_PERIOD.aiEligible()).isFalse();
        assertThat(Concepts.SALARY_EXPECTATION.aiEligible()).isFalse();
        assertThat(Concepts.EDUCATION_DEGREE.aiEligible()).isFalse();
    }

    @Test
    @DisplayName("nothing a model writes is auto-fillable")
    void aiEligibleConceptsAreNeverAutoResolvable() {
        Concepts.all().stream()
                .filter(Concept::aiEligible)
                .forEach(concept -> assertThat(concept.autoResolvable())
                        .as("%s is drafted by a model and must be read first", concept.id())
                        .isFalse());
    }

    @Test
    @DisplayName("an unrecognised question is a value, not a null")
    void unrecognisedIsAValue() {
        assertThat(Concepts.byAlias("What is your favourite kind of sandwich?"))
                .isEmpty();
        assertThat(Concept.UNRECOGNISED.isUnrecognised()).isTrue();
        assertThat(Concept.UNRECOGNISED.aiEligible()).isFalse();
    }

    @Test
    @DisplayName("volatile concepts are the ones that go out of date on their own")
    void volatileConceptsAreMarked() {
        assertThat(Concepts.NOTICE_PERIOD.volatileFact()).isTrue();
        assertThat(Concepts.SALARY_EXPECTATION.volatileFact()).isTrue();
        assertThat(Concepts.START_DATE.volatileFact()).isTrue();
        assertThat(Concepts.EDUCATION_DEGREE.volatileFact()).isFalse();
        assertThat(Concepts.FIRST_NAME.volatileFact()).isFalse();
    }
}
