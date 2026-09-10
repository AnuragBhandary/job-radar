package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Moving an answer off a made-up key and onto a real concept.
 *
 * <p>Three answers in the live database are filed under keys built by truncating
 * the question at fifty characters. Nothing resolves against those, so every form
 * asking the same question in a different wording asked him again - and the wide
 * shadow run measured the cost precisely: 243 comparisons where the old flat
 * matcher answered and the resolver could not.
 *
 * <p>These tests are about doing that safely. The value must not change, the old
 * row must survive, and the scope rules must apply exactly as they do to every
 * other write.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LegacyAdoptionTest {

    @Autowired
    private AssertionRepository assertions;

    private KnowledgeService knowledge;

    @BeforeEach
    void setUp() {
        assertions.deleteAll();
        knowledge = new KnowledgeService(assertions, new SessionAnswers());
    }

    private Assertion legacy(String key, String question, String answer) {
        Assertion assertion = new Assertion(key, answer, Scope.application(1L),
                KnowledgeSource.USER_RULE);
        assertion.setSourceQuestion(question);
        assertion.setNeedsReview(true);
        return assertions.save(assertion);
    }

    @Test
    @DisplayName("an answer keeps its words and gains a concept that can resolve it")
    void adoptionRekeysWithoutRewriting() {
        Assertion stored = legacy("legacy.how_do_you_like_to_work_with_other_engineers",
                "how do you like to work with other engineers",
                "I actively seek reviews and pitch in on other people's work.");

        Assertion adopted = knowledge.adopt(stored.getId(), Concepts.COLLABORATION_STYLE,
                "test");

        assertThat(adopted.getConceptId()).isEqualTo(Concepts.COLLABORATION_STYLE.id());
        // The words are his and are not touched.
        assertThat(adopted.getValue()).isEqualTo(stored.getValue());
        assertThat(adopted.isUsable()).isTrue();
        // And the wording that produced it survives, which is what makes a wrong
        // adoption diagnosable later.
        assertThat(adopted.getSourceQuestion())
                .isEqualTo("how do you like to work with other engineers");
    }

    @Test
    @DisplayName("the old row is superseded, not deleted")
    void historySurvives() {
        Assertion stored = legacy("legacy.which_working_setup_gets_the_best_work_out_of_yo",
                "which working setup gets the best work out of you",
                "An open-ended problem I can dig into.");

        Assertion adopted = knowledge.adopt(stored.getId(), Concepts.WORKING_STYLE, "test");

        Assertion old = assertions.findById(stored.getId()).orElseThrow();
        assertThat(old.getSupersededById()).isEqualTo(adopted.getId());
        assertThat(old.isLive()).isFalse();
        assertThat(old.isNeedsReview()).isFalse();
        // The record says where it went and why it was moved.
        assertThat(adopted.getNote()).contains("adopted from").contains("legacy.");
    }

    @Test
    @DisplayName("the question's wording is what finds its concept")
    void aliasesFindTheHome() {
        assertThat(Concepts.byAlias("how do you like to work with other engineers"))
                .contains(Concepts.COLLABORATION_STYLE);
        assertThat(Concepts.byAlias("which working setup gets the best work out of you"))
                .contains(Concepts.WORKING_STYLE);
        assertThat(Concepts.byAlias(
                "Which best describes your experience with event-driven systems?"))
                .contains(Concepts.EVENT_DRIVEN_EXPERIENCE);
    }

    @Test
    @DisplayName("adoption obeys the same scope rules as every other write")
    void scopeSafetyStillApplies() {
        Assertion stored = legacy("legacy.sponsorship_thing",
                "do you require sponsorship", "Yes");

        // Sponsorship is context-sensitive, so there is no global to adopt into
        // and no country to guess. It stays where it is.
        assertThatThrownBy(() -> knowledge.adopt(stored.getId(),
                Concepts.SPONSORSHIP_REQUIRED, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yours to choose");

        assertThat(assertions.findById(stored.getId()).orElseThrow().isLive()).isTrue();
    }

    @Test
    @DisplayName("an answer whose wording matches nothing is left alone")
    void unmatchedQuestionsAreNotForced() {
        legacy("legacy.something_bespoke",
                "what is the airspeed velocity of an unladen swallow", "African or European?");

        assertThat(Concepts.byAlias("what is the airspeed velocity of an unladen swallow"))
                .isEmpty();
    }

    @Test
    @DisplayName("after adoption the concept actually resolves")
    void theAnswerNowWorks() {
        Assertion stored = legacy("legacy.experience_with_event_driven_systems",
                "experience with event-driven systems",
                "I've built event-driven components.");
        knowledge.adopt(stored.getId(), Concepts.EVENT_DRIVEN_EXPERIENCE, "test");

        Optional<Assertion> live = assertions
                .findByConceptIdAndSupersededByIdIsNull(Concepts.EVENT_DRIVEN_EXPERIENCE.id())
                .stream().findFirst();

        assertThat(live).isPresent();
        assertThat(live.get().isUsable()).isTrue();
        assertThat(live.get().scope()).isEqualTo(Scope.global());
    }
}
