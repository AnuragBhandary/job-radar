package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Turning a semantic yes or no into the sentence a form actually offers.
 *
 * <p>Every option string here is one a real board uses. The two that matter most
 * are the sponsorship pair and the work-authorisation pair, because they are
 * asked in opposite polarity, they are asked on nearly every international form,
 * and both are auto-reject triggers - getting one backwards is worse than leaving
 * it blank.
 *
 * <p>So the tests are as much about what this refuses as about what it picks.
 */
class SemanticOptionsTest {

    private static String chosen(Concept concept, String question, String answer,
            List<String> options) {
        return SemanticOptions.choose(concept, question, answer, options)
                .option().orElse(null);
    }

    @Nested
    @DisplayName("sponsorship")
    class Sponsorship {

        private static final Concept CONCEPT = Concepts.SPONSORSHIP_REQUIRED;
        private static final String QUESTION = "Do you require sponsorship?";

        @Test
        @DisplayName("yes picks the option that says he needs it")
        void yesPicksTheAffirmingOption() {
            assertThat(chosen(CONCEPT, QUESTION, "Yes",
                    List.of("I need sponsorship", "I do not need sponsorship")))
                    .isEqualTo("I need sponsorship");
        }

        @Test
        @DisplayName("no picks the option that says he does not")
        void noPicksTheDenyingOption() {
            assertThat(chosen(CONCEPT, QUESTION, "No",
                    List.of("I will require sponsorship", "I will not require sponsorship")))
                    .isEqualTo("I will not require sponsorship");
        }

        @Test
        @DisplayName("the wording the boards actually use, in both directions")
        void realPhrasings() {
            List<String> options = List.of(
                    "I currently need visa sponsorship",
                    "No sponsorship will be needed");

            assertThat(chosen(CONCEPT, QUESTION, "Yes", options))
                    .isEqualTo("I currently need visa sponsorship");
            assertThat(chosen(CONCEPT, QUESTION, "No", options))
                    .isEqualTo("No sponsorship will be needed");
        }

        @Test
        @DisplayName("an option beginning with the answer still wins on the words alone")
        void theWordMatcherStillRunsFirst() {
            assertThat(chosen(CONCEPT, QUESTION, "Yes",
                    List.of("Yes, I will now or in the future require sponsorship",
                            "No, I do not require sponsorship")))
                    .isEqualTo("Yes, I will now or in the future require sponsorship");
        }

        @Test
        @DisplayName("'now' inside an option never reads as 'no'")
        void nowIsNotNo() {
            assertThat(chosen(CONCEPT, QUESTION, "No",
                    List.of("Yes, I will now or in the future require sponsorship",
                            "No, I do not require sponsorship")))
                    .isEqualTo("No, I do not require sponsorship");
        }
    }

    @Nested
    @DisplayName("work authorisation - asked in the opposite polarity")
    class WorkAuthorisation {

        private static final Concept CONCEPT = Concepts.WORK_AUTHORISATION;
        private static final String QUESTION = "Are you authorized to work in Germany?";

        @Test
        @DisplayName("yes picks the option that says he is authorised")
        void yesIsAuthorised() {
            assertThat(chosen(CONCEPT, QUESTION, "Yes",
                    List.of("I am legally authorized", "I am not legally authorized")))
                    .isEqualTo("I am legally authorized");
        }

        @Test
        @DisplayName("no picks the option that says he is not")
        void noIsNotAuthorised() {
            assertThat(chosen(CONCEPT, QUESTION, "No",
                    List.of("I am legally authorized", "I am not legally authorized")))
                    .isEqualTo("I am not legally authorized");
        }

        @Test
        @DisplayName("citizenship and residency wording counts as authorised")
        void citizenshipWording() {
            assertThat(chosen(CONCEPT, QUESTION, "Yes",
                    List.of("I am a citizen or permanent resident of this country",
                            "I will need a work permit")))
                    .isEqualTo("I am a citizen or permanent resident of this country");
        }

        @Test
        @DisplayName("the same answer means opposite things on the two questions")
        void polarityIsPerQuestionNotPerWord() {
            // "Yes" on one form means he needs a visa and on the other that he
            // does not. Nothing here may resolve that by looking for a word.
            String sponsorship = chosen(Concepts.SPONSORSHIP_REQUIRED,
                    "Do you require sponsorship?", "Yes",
                    List.of("I require sponsorship", "I do not require sponsorship"));
            String authorised = chosen(Concepts.WORK_AUTHORISATION,
                    "Are you authorized to work here?", "Yes",
                    List.of("I am authorized to work here",
                            "I am not authorized to work here"));

            assertThat(sponsorship).isEqualTo("I require sponsorship");
            assertThat(authorised).isEqualTo("I am authorized to work here");
        }
    }

    @Nested
    @DisplayName("a question phrased in the negative")
    class NegatedQuestion {

        @Test
        @DisplayName("self-describing options ignore the question's polarity")
        void selfDescribingOptionsStateTheTruth() {
            // "I am not authorized" is true or false regardless of how the
            // question was worded, so the option is chosen on the fact.
            assertThat(chosen(Concepts.WORK_AUTHORISATION,
                    "Are you NOT authorized to work in this country?", "Yes",
                    List.of("I am authorized to work here",
                            "I am not authorized to work here")))
                    .isEqualTo("I am authorized to work here");
        }

        @Test
        @DisplayName("a bare yes/no against a negated question inverts")
        void bareOptionsInvert() {
            // The options carry no meaning of their own, so the question's
            // wording is the only thing that decides. He is authorised, so the
            // answer to "are you NOT authorised" is no.
            SemanticOptions.Match match = SemanticOptions.choose(
                    Concepts.WORK_AUTHORISATION,
                    "Are you not authorized to work in this country?", "Yes",
                    List.of("Yes", "No"));

            assertThat(match.option()).contains("No");
            assertThat(match.why()).contains("phrased in the negative");
        }

        @Test
        @DisplayName("an ordinary question is never inverted")
        void ordinaryQuestionsAreLeftAlone() {
            assertThat(SemanticOptions.choose(Concepts.WORK_AUTHORISATION,
                    "Are you authorized to work in this country?", "Yes",
                    List.of("Yes", "No")).option()).contains("Yes");
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("options with no recognisable meaning are left unmatched")
        void unclearOptionsAreUnmatched() {
            SemanticOptions.Match match = SemanticOptions.choose(
                    Concepts.SPONSORSHIP_REQUIRED, "Do you require sponsorship?", "Yes",
                    List.of("Option A", "Option B"));

            assertThat(match.matched()).isFalse();
            assertThat(match.why()).contains("nowhere to go");
        }

        @Test
        @DisplayName("two options saying the same thing is a guess, so it refuses")
        void ambiguousOptionsAreRefused() {
            SemanticOptions.Match match = SemanticOptions.choose(
                    Concepts.SPONSORSHIP_REQUIRED, "Do you require sponsorship?", "Yes",
                    List.of("I require sponsorship now",
                            "I require sponsorship in the future",
                            "I do not require sponsorship"));

            assertThat(match.matched()).isFalse();
            assertThat(match.why()).contains("guess");
        }

        @Test
        @DisplayName("a concept with no known polarity is never matched semantically")
        void unknownConceptsAreNotGuessedAt() {
            SemanticOptions.Match match = SemanticOptions.choose(
                    Concept.UNRECOGNISED, "Do you have a forklift licence?", "Yes",
                    List.of("I hold a licence", "I do not hold a licence"));

            assertThat(match.matched()).isFalse();
            assertThat(match.why()).contains("no known polarity");
        }

        @Test
        @DisplayName("an answer that is neither yes nor no has no polarity to match")
        void nonBooleanAnswers() {
            SemanticOptions.Match match = SemanticOptions.choose(
                    Concepts.SPONSORSHIP_REQUIRED, "Do you require sponsorship?",
                    "It depends on the role", List.of("I need it", "I do not need it"));

            assertThat(match.matched()).isFalse();
        }

        @Test
        @DisplayName("nothing to choose between is not an error")
        void emptyInputsAreSafe() {
            assertThat(SemanticOptions.choose(Concepts.SPONSORSHIP_REQUIRED, "q", "Yes",
                    List.of()).matched()).isFalse();
            assertThat(SemanticOptions.choose(Concepts.SPONSORSHIP_REQUIRED, "q", null,
                    List.of("a")).matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("the other boolean questions")
    class OtherConcepts {

        @Test
        @DisplayName("relocation")
        void relocation() {
            assertThat(chosen(Concepts.RELOCATION_WILLING, "Are you willing to relocate?",
                    "Yes", List.of("I am willing to relocate",
                            "I am not willing to relocate")))
                    .isEqualTo("I am willing to relocate");
        }

        @Test
        @DisplayName("having worked here before")
        void workedHereBefore() {
            assertThat(chosen(Concepts.WORKED_HERE_BEFORE,
                    "Have you worked for us before?", "No",
                    List.of("I have worked here before",
                            "I have not worked here before")))
                    .isEqualTo("I have not worked here before");
        }

        @Test
        @DisplayName("being related to an employee")
        void relatedToEmployee() {
            assertThat(chosen(Concepts.RELATED_TO_EMPLOYEE,
                    "Are you related to a current employee?", "No",
                    List.of("I am related to an employee",
                            "I am not related to any employee")))
                    .isEqualTo("I am not related to any employee");
        }
    }
}
