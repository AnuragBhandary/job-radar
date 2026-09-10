package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A stored answer that is not an answer must never beat one that is.
 *
 * <p>Written after two rows turned up in the live database holding a rule
 * <em>explained in prose</em> where a yes or no belonged - "In all countries
 * other than India, the answer is yes, I need sponsorship" - stored against a
 * boolean concept, approved, live, and scoped to Germany.
 *
 * <p>Everything about that row was working as designed. It was a USER_RULE, and a
 * user rule outranks a derivation, so it won; the derivation underneath got
 * Germany right and never got the chance to say so. What would have reached a
 * radio button was the whole sentence.
 *
 * <p>The fix is not to weaken source authority - an approved rule beating a
 * computed answer is correct and stays. It is that validity is checked
 * <em>before</em> anything is ranked, so a malformed row is not a weaker
 * candidate, it is not a candidate.
 */
class AssertionValidityTest {

    private final List<Assertion> stored = new ArrayList<>();
    private KnowledgeResolver resolver;

    @BeforeEach
    void setUp() {
        stored.clear();
        SessionAnswers session = new SessionAnswers();
        session.clearAll();

        AssertionRepository repository = mock(AssertionRepository.class);
        when(repository.findByConceptIdAndSupersededByIdIsNull(anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(a -> a.getConceptId().equals(call.getArgument(0)))
                        .filter(Assertion::isLive)
                        .toList());

        var profile = TestProfiles.indianApplicant();
        resolver = new KnowledgeResolver(repository, new ProfileFacts(profile),
                new Derivations(profile), session, new FieldClassifier());
    }

    private Assertion store(Concept concept, String value, Scope scope) {
        Assertion assertion = new Assertion(concept.id(), value, scope,
                KnowledgeSource.USER_RULE);
        assertion.approve("test");
        stored.add(assertion);
        return assertion;
    }

    // ------------------------------------------------------------------
    // What counts as an answer
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "a boolean concept accepts \"{0}\"")
    @ValueSource(strings = {"Yes", "No", "yes", "TRUE", "N/A",
            "Yes, I am legally authorized to work in this country",
            "No, I do not require sponsorship",
            "I am a citizen or permanent resident"})
    @DisplayName("the option strings boards actually use are valid boolean answers")
    void realOptionStringsAreValid(String value) {
        assertThat(Concept.AnswerType.BOOLEAN.accepts(value)).as(value).isTrue();
    }

    @ParameterizedTest(name = "a boolean concept refuses \"{0}\"")
    @ValueSource(strings = {
            "In all countries other than India, the answer is yes, I need sponsorship. "
                    + "I do not need sponsorship to work in India.",
            "It depends. In Germany I would need one, and in India I would not.",
            "Yes for some countries. No for others."})
    @DisplayName("a rule explained in prose is not a boolean answer")
    void proseIsNotABoolean(String value) {
        assertThat(Concept.AnswerType.BOOLEAN.accepts(value)).as(value).isFalse();
    }

    @Test
    @DisplayName("a number concept wants a number and a date concept wants a date")
    void theOtherTypesAreChecked() {
        assertThat(Concept.AnswerType.NUMBER.accepts("1")).isTrue();
        assertThat(Concept.AnswerType.NUMBER.accepts("3 years")).isTrue();
        assertThat(Concept.AnswerType.NUMBER.accepts("about a year and a half")).isFalse();

        assertThat(Concept.AnswerType.DATE.accepts("2027-06-01")).isTrue();
        assertThat(Concept.AnswerType.DATE.accepts("Immediately")).isTrue();
        assertThat(Concept.AnswerType.DATE.accepts(
                "Whenever suits, though I would need a month to hand over properly"))
                .isFalse();
    }

    @Test
    @DisplayName("an absent value is not a malformed one")
    void blankIsNotInvalid() {
        assertThat(Concept.AnswerType.BOOLEAN.accepts(null)).isTrue();
        assertThat(Concept.AnswerType.BOOLEAN.accepts("  ")).isTrue();
    }

    // ------------------------------------------------------------------
    // What that does to resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a malformed rule loses to the derivation it used to beat")
    void theDerivationWinsAgainstAMalformedRule() {
        Assertion bad = store(Concepts.SPONSORSHIP_REQUIRED,
                "In all countries other than India, the answer is yes, I need sponsorship. "
                        + "I do not need sponsorship to work in India.",
                Scope.country("DE"));

        // Before it is marked, the rule wins - which is the bug, and worth
        // asserting so the fix cannot be quietly undone.
        assertThat(resolver.resolve(Concepts.SPONSORSHIP_REQUIRED, Contexts.germanyOnsite())
                .source()).isEqualTo(KnowledgeSource.USER_RULE);

        bad.markInvalid("this is a sentence, and the question wants a yes or a no");

        Resolution after = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());
        assertThat(after.source()).isEqualTo(KnowledgeSource.DERIVED);
        assertThat(after.value()).isEqualTo("Yes");
        assertThat(after.isAutoFillable()).isTrue();
    }

    @Test
    @DisplayName("a well-formed rule still outranks a derivation")
    void sourceAuthorityIsNotWeakened() {
        // The point of the fix: validity is a gate, not a demotion. An approved
        // answer he typed still beats a computed one.
        store(Concepts.SPONSORSHIP_REQUIRED, "No", Scope.country("DE"));

        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());

        assertThat(resolution.source()).isEqualTo(KnowledgeSource.USER_RULE);
        assertThat(resolution.value()).isEqualTo("No");
    }

    @Test
    @DisplayName("a malformed row is not a candidate, so it cannot create a conflict")
    void malformedRowsDoNotConflict() {
        store(Concepts.SPONSORSHIP_REQUIRED, "Yes", Scope.country("DE"));
        Assertion bad = store(Concepts.SPONSORSHIP_REQUIRED,
                "It depends on the country. In Germany yes.", Scope.country("DE"));
        bad.markInvalid("this is a sentence, and the question wants a yes or a no");

        Resolution resolution = resolver.resolve(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite());

        assertThat(resolution.state()).isNotEqualTo(Resolution.State.CONFLICT);
        assertThat(resolution.value()).isEqualTo("Yes");
    }

    @Test
    @DisplayName("marking a row invalid keeps its value and its history")
    void nothingIsDestroyed() {
        Assertion bad = store(Concepts.SPONSORSHIP_REQUIRED,
                "In all countries other than India, the answer is yes. Not in India.",
                Scope.country("DE"));
        String original = bad.getValue();

        bad.markInvalid("this is a sentence, and the question wants a yes or a no");

        assertThat(bad.getValue()).isEqualTo(original);
        assertThat(bad.isLive()).isTrue();
        assertThat(bad.isUsable()).isFalse();
        assertThat(bad.isNeedsReview()).isTrue();
        assertThat(bad.getInvalidReason()).contains("yes or a no");
    }

    @Test
    @DisplayName("the trace says why a rule was not used")
    void theTraceExplainsTheExclusion() {
        Assertion bad = store(Concepts.SPONSORSHIP_REQUIRED,
                "In all countries other than India, the answer is yes. Not in India.",
                Scope.country("DE"));
        bad.markInvalid("this is a sentence, and the question wants a yes or a no");

        String trace = resolver.trace(Concepts.SPONSORSHIP_REQUIRED,
                Contexts.germanyOnsite(), java.time.LocalDate.now());

        assertThat(trace).contains("not a usable answer");
    }

    @Test
    @DisplayName("an invalid row never counts as knowledge in use")
    void invalidRowsAreNotUsable() {
        Assertion bad = store(Concepts.VALID_PASSPORT, "Yes", Scope.global());
        assertThat(bad.isUsable()).isTrue();

        bad.markInvalid("test");
        assertThat(bad.isUsable()).isFalse();
    }
}
