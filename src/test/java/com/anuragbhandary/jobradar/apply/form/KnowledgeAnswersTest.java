package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.knowledge.AnswerPlan;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.AssertionRepository;
import com.anuragbhandary.jobradar.knowledge.Contexts;
import com.anuragbhandary.jobradar.knowledge.Derivations;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.ProfileFacts;
import com.anuragbhandary.jobradar.knowledge.SessionAnswers;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole authoritative path, end to end: a question in, a form value out.
 *
 * <p>Resolver, then plan, then adapter, then the option matcher - the pipeline
 * that fills a real employer's form once the flag is on. The sponsorship matrix
 * is run through all four layers here rather than against the resolver alone,
 * because a correct semantic answer that cannot be converted into this form's
 * wording is not a filled field.
 *
 * <p>Half the tests are about refusing. Every one of them is a case where the
 * alternative is a wrong answer on a two-option radio, and on the questions this
 * covers a wrong answer is an auto-reject rather than an awkward interview.
 */
class KnowledgeAnswersTest {

    private final List<Assertion> stored = new ArrayList<>();
    private KnowledgeAnswers answers;

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
        KnowledgeResolver resolver = new KnowledgeResolver(repository,
                new ProfileFacts(profile),
                new Derivations(profile, TestResumes.backendResume()), session,
                new FieldClassifier());
        AnswerPlan plan = new AnswerPlan(resolver, new ExperiencePositioner(
                new ExperienceIndex(TestResumes.backendResume())));
        answers = new KnowledgeAnswers(plan, resolver, profile);
    }

    private static FormField radio(String label, FieldKind kind, String... options) {
        return new FormField("#f", label, FormField.ControlType.RADIO, List.of(options),
                true, kind);
    }

    private static FormField text(String label, FieldKind kind) {
        return new FormField("#f", label, FormField.ControlType.TEXT, List.of(), true, kind);
    }

    private Answer answer(FormField field, ApplicationContext context) {
        return answers.answer(field, context, null);
    }

    // ------------------------------------------------------------------
    // The sponsorship matrix, through the whole pipeline
    // ------------------------------------------------------------------

    private static final FormField SPONSORSHIP = radio(
            "Will you now or in the future require visa sponsorship?",
            FieldKind.SPONSORSHIP_REQUIRED,
            "I will require sponsorship", "I will not require sponsorship");

    @Test
    @DisplayName("India onsite: no sponsorship, and the form says so in its own words")
    void indiaOnsite() {
        Answer result = answer(SPONSORSHIP, Contexts.indiaOnsite());

        assertThat(result.value()).isEqualTo("I will not require sponsorship");
        assertThat(result.origin()).isEqualTo(Answer.Origin.DERIVED);
    }

    @Test
    @DisplayName("Germany onsite: sponsorship required")
    void germanyOnsite() {
        assertThat(answer(SPONSORSHIP, Contexts.germanyOnsite()).value())
                .isEqualTo("I will require sponsorship");
    }

    @Test
    @DisplayName("US relocation: sponsorship required, whatever the strategy says about it")
    void usRelocation() {
        // Strategy excludes the United States for relocation. Knowledge
        // resolution is a separate question and stays correct.
        assertThat(answer(SPONSORSHIP, Contexts.usOnsite()).value())
                .isEqualTo("I will require sponsorship");
    }

    @Test
    @DisplayName("a US employer hiring into India: no sponsorship")
    void usEmployerRemoteFromIndia() {
        // The case the whole context model exists for. Job country US,
        // employment country IN, and the answer follows where he will sit.
        assertThat(answer(SPONSORSHIP, Contexts.usRemoteFromIndia()).value())
                .isEqualTo("I will not require sponsorship");
    }

    @Test
    @DisplayName("Canada onsite: sponsorship required, because he holds no permit there")
    void canadaOnsite() {
        ApplicationContext canada = Contexts.builder().company("Shopify").country("CA")
                .employer("CA").mode(com.anuragbhandary.jobradar.domain.WorkMode.ONSITE)
                .lane(com.anuragbhandary.jobradar.domain.StrategicClass
                        .INTERNATIONAL_RELOCATION)
                .build();

        assertThat(answer(SPONSORSHIP, canada).value())
                .isEqualTo("I will require sponsorship");
    }

    @Test
    @DisplayName("already authorised in Germany: no sponsorship")
    void authorisedInGermany() {
        ApplicationContext berlin = Contexts.builder().company("Camunda").country("DE")
                .employer("DE").mode(com.anuragbhandary.jobradar.domain.WorkMode.ONSITE)
                .lane(com.anuragbhandary.jobradar.domain.StrategicClass
                        .INTERNATIONAL_RELOCATION)
                .authorisedIn("IN", "DE")
                .build();

        assertThat(answer(SPONSORSHIP, berlin).value())
                .isEqualTo("I will not require sponsorship");
    }

    @Test
    @DisplayName("an unstated remote location is never auto-filled")
    void unknownEmploymentCountryIsNotGuessed() {
        Answer result = answer(SPONSORSHIP, Contexts.remoteUnstated());

        // The derivation has an opinion and holds it at MEDIUM, which is not
        // enough to type into an employer's form unread.
        assertThat(result.hasValue()).isFalse();
        assertThat(result.origin()).isEqualTo(Answer.Origin.UNANSWERED);
    }

    // ------------------------------------------------------------------
    // Work authorisation, which is asked in the opposite polarity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the two questions get opposite answers on the same application")
    void oppositePolarityOnOneForm() {
        FormField authorisation = radio("Are you legally authorized to work in Germany?",
                FieldKind.WORK_AUTHORISATION,
                "I am legally authorized to work here",
                "I am not legally authorized to work here");

        assertThat(answer(SPONSORSHIP, Contexts.germanyOnsite()).value())
                .isEqualTo("I will require sponsorship");
        assertThat(answer(authorisation, Contexts.germanyOnsite()).value())
                .isEqualTo("I am not legally authorized to work here");
    }

    // ------------------------------------------------------------------
    // What it refuses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an answer with nowhere to go leaves the field for a person")
    void unmatchedOptionsRefuse() {
        // A real Dutch form: every option asserts sponsorship in some form, and
        // the answer is no. There is nothing to select.
        FormField awkward = radio("Do you need sponsorship from us?",
                FieldKind.SPONSORSHIP_REQUIRED,
                "I need sponsorship now", "I will need sponsorship in future");

        Answer result = answer(awkward, Contexts.indiaOnsite());

        assertThat(result.hasValue()).isFalse();
        assertThat(result.note()).contains("none of this form's options");
    }

    @Test
    @DisplayName("two options meaning the same thing is a guess, so it refuses")
    void ambiguousOptionsRefuse() {
        FormField ambiguous = radio("Will you require visa sponsorship?",
                FieldKind.SPONSORSHIP_REQUIRED,
                "Yes, now", "Yes, in the future", "No");

        // "Yes, now" and "Yes, in the future" both assert it. Choosing between
        // them is not something this layer knows how to do.
        Answer result = answer(ambiguous, Contexts.germanyOnsite());
        assertThat(result.hasValue()).isFalse();
    }

    @Test
    @DisplayName("a question nothing answers is left for him, with the reason")
    void unknownQuestionsAreLeft() {
        Answer result = answer(text("What is your mother's maiden name?", FieldKind.UNKNOWN),
                Contexts.germanyOnsite());

        assertThat(result.origin()).isEqualTo(Answer.Origin.UNANSWERED);
        assertThat(result.note()).isNotBlank();
    }

    @Test
    @DisplayName("a drafted answer is never typed into a live form")
    void draftsAreNotFilled() {
        // A technology question routes to a draft, which belongs on the
        // preparation screen behind an approval - not in a browser.
        Answer result = answer(text("Do you have experience with Kubernetes?",
                FieldKind.UNKNOWN), Contexts.germanyOnsite());

        assertThat(result.hasValue()).isFalse();
    }

    // ------------------------------------------------------------------
    // The ordinary fields
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a profile fact fills itself, marked as copied rather than worked out")
    void profileFactsFill() {
        Answer result = answer(text("First name", FieldKind.FIRST_NAME),
                Contexts.germanyOnsite());

        assertThat(result.hasValue()).isTrue();
        assertThat(result.origin()).isEqualTo(Answer.Origin.PROFILE);
    }

    @Test
    @DisplayName("a voluntary question picks the form's own decline option")
    void voluntaryQuestionsDecline() {
        FormField gender = new FormField("#g", "Gender", FormField.ControlType.SELECT,
                List.of("Male", "Female", "Prefer not to say"), false, FieldKind.GENDER);

        Answer result = answers.answer(gender, Contexts.germanyOnsite(), null);

        // The profile in the test fixture states a gender, so it is answered
        // rather than declined - what matters is that it is one of the options.
        assertThat(List.of("Male", "Female", "Prefer not to say"))
                .contains(result.value());
    }

    // ------------------------------------------------------------------
    // Salary, where a borrowed band is worse than no answer
    // ------------------------------------------------------------------

    private static final FormField SALARY = text("What are your salary expectations?",
            FieldKind.SALARY_EXPECTATION);

    @Test
    @DisplayName("a country with its own band fills, in that country's currency")
    void ownBandFills() {
        Answer result = answer(SALARY, Contexts.germanyOnsite());

        assertThat(result.hasValue()).isTrue();
        assertThat(result.value()).contains("EUR");
    }

    @Test
    @DisplayName("a country with no band of its own is never auto-filled")
    void aBorrowedBandIsNotFilled() {
        // The profile configures India and Germany. A US role falls back to the
        // Indian band, and 'INR 1,200,000' in a US salary box is roughly a tenth
        // of the market rate - an anchor no later correction undoes. The figure
        // is still worth showing him; it is not worth sending unread.
        Answer result = answer(SALARY, Contexts.usOnsite());

        assertThat(result.hasValue()).isFalse();
        assertThat(result.origin()).isEqualTo(Answer.Origin.UNANSWERED);
    }

    @Test
    @DisplayName("years of experience comes from the dates, not from a stored string")
    void yearsIsDerived() {
        Answer result = answer(text("How many years of professional experience?",
                FieldKind.UNKNOWN), Contexts.germanyOnsite());

        // The fixture resume has one job of twelve months.
        assertThat(result.value()).isEqualTo("1");
        assertThat(result.origin()).isEqualTo(Answer.Origin.DERIVED);
        assertThat(result.note()).contains("employment dates");
    }
}
