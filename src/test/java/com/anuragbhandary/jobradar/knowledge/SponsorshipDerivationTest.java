package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical regression case, and the reason the knowledge package exists.
 *
 * <p>Pure: profile plus context in, resolution out. No database, no browser, no
 * model. Both questions are asked on nearly every form, in opposite polarity, and
 * both are auto-reject triggers - so being able to run the whole matrix in
 * milliseconds is what makes it safe to change any of it.
 */
class SponsorshipDerivationTest {

    private final Derivations derivations = new Derivations(TestProfiles.indianApplicant());

    private String sponsorship(ApplicationContext context) {
        return value(derivations.derive(Concepts.SPONSORSHIP_REQUIRED, context));
    }

    private String authorisation(ApplicationContext context) {
        return value(derivations.derive(Concepts.WORK_AUTHORISATION, context));
    }

    private static String value(Optional<Resolution> resolution) {
        return resolution.map(Resolution::value).orElse(null);
    }

    private Resolution resolve(ApplicationContext context) {
        return derivations.derive(Concepts.SPONSORSHIP_REQUIRED, context).orElseThrow();
    }

    // ------------------------------------------------------------------
    // The six cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("case 1 - India, onsite, authorised: no sponsorship")
    void indiaOnsite() {
        assertThat(sponsorship(Contexts.indiaOnsite())).isEqualTo("No");
        assertThat(authorisation(Contexts.indiaOnsite())).isEqualTo("Yes");
        assertThat(resolve(Contexts.indiaOnsite()).confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("case 2 - Germany, onsite, not authorised: sponsorship required")
    void germanyOnsite() {
        assertThat(sponsorship(Contexts.germanyOnsite())).isEqualTo("Yes");
        assertThat(authorisation(Contexts.germanyOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("case 3 - US relocation: sponsorship required, and the answer is honest")
    void usRelocation() {
        // The strategy excludes US relocation, and the strategy has nothing to do
        // with this answer. If the form is being filled at all, "yes" is true.
        assertThat(sponsorship(Contexts.usOnsite())).isEqualTo("Yes");
        assertThat(authorisation(Contexts.usOnsite())).isEqualTo("No");
    }

    @Test
    @DisplayName("case 4 - US employer, remote from India: NOT a US relocation, no sponsorship")
    void usRemoteFromIndia() {
        ApplicationContext context = Contexts.usRemoteFromIndia();

        assertThat(context.countryCode()).isEqualTo("US");
        // The employment country is where he sits, and that is what governs.
        assertThat(context.employmentCountryCode()).isEqualTo("IN");
        assertThat(context.strategicClass()).isEqualTo(StrategicClass.INTERNATIONAL_REMOTE);

        assertThat(sponsorship(context)).isEqualTo("No");
        assertThat(authorisation(context)).isEqualTo("Yes");
        assertThat(resolve(context).confidence()).isEqualTo(Confidence.HIGH);
        assertThat(resolve(context).explanation()).contains("not a relocation");
    }

    @Test
    @DisplayName("case 5 - remote, US only: not India-eligible, and not papered over")
    void usOnlyRemote() {
        ApplicationContext context = Contexts.usOnlyRemote();

        // He may not sit in India, so the employment country stays the US.
        assertThat(context.employmentCountryCode()).isEqualTo("US");
        assertThat(context.statesRemoteFromHome()).isFalse();

        // Answered honestly rather than answered "No" to make the job look
        // takeable. Screening has already refused this posting; the resolver's
        // job is not to disguise it.
        assertThat(sponsorship(context)).isEqualTo("Yes");
        assertThat(resolve(context).explanation()).contains("United States");
    }

    @Test
    @DisplayName("case 6 - already authorised in the employment country: no sponsorship")
    void alreadyAuthorisedAbroad() {
        // Nationality is not the input. If he holds a German permit then a German
        // job needs no sponsorship, and answering "yes" because he is Indian
        // would be both wrong and unnecessary - it invites a visa conversation
        // about a visa nobody needs.
        Derivations withGermanPermit =
                new Derivations(authorisedIn(Country.INDIA, Country.GERMANY));
        ApplicationContext german = Contexts.builder()
                .company("Camunda").country("DE").employer("DE")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INTERNATIONAL_RELOCATION)
                .authorisedIn("IN", "DE")
                .build();

        assertThat(value(withGermanPermit.derive(Concepts.SPONSORSHIP_REQUIRED, german)))
                .isEqualTo("No");
        assertThat(value(withGermanPermit.derive(Concepts.WORK_AUTHORISATION, german)))
                .isEqualTo("Yes");

        // And the same profile still needs sponsorship somewhere it has no permit.
        ApplicationContext irish = Contexts.builder()
                .company("Stripe").country("IE").employer("IE")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INTERNATIONAL_RELOCATION)
                .authorisedIn("IN", "DE")
                .build();
        assertThat(value(withGermanPermit.derive(Concepts.SPONSORSHIP_REQUIRED, irish)))
                .isEqualTo("Yes");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Canada remote from India resolves like any other international remote")
    void canadaRemoteFromIndia() {
        assertThat(sponsorship(Contexts.canadaRemoteFromIndia())).isEqualTo("No");
        assertThat(Contexts.canadaRemoteFromIndia().employmentCountryCode()).isEqualTo("IN");
    }

    @Test
    @DisplayName("a bare Remote suggests the home-country answer and refuses to fill it")
    void remoteWithNoStatedScope() {
        // Both directions hurt if wrong: claiming a permit he does not hold
        // surfaces at the offer stage, and declaring a need for one he does not
        // need is an auto-reject. So the derivation does not pick a "safe"
        // direction - it declines to fill unread, and offers the likeliest
        // reading beside it.
        ApplicationContext context = Contexts.remoteUnstated();
        assertThat(context.employmentCountryCode()).isNull();

        Resolution sponsorship = resolve(context);
        assertThat(sponsorship.value()).isEqualTo("No");
        assertThat(sponsorship.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(sponsorship.isAutoFillable()).isFalse();
        assertThat(sponsorship.needsReview()).isTrue();
        assertThat(sponsorship.explanation()).contains("never says where from");
        assertThat(sponsorship.explanation()).contains("check it before this is sent");

        // And the paired question stays inverted, at the same confidence.
        Resolution authorisation =
                derivations.derive(Concepts.WORK_AUTHORISATION, context).orElseThrow();
        assertThat(authorisation.value()).isEqualTo("Yes");
        assertThat(authorisation.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(authorisation.isAutoFillable()).isFalse();
    }

    @Test
    @DisplayName("the documents a preparation rendered are answers the resolver can give")
    void documentsAreAnswerable() {
        ApplicationContext withDocuments = Contexts.builder()
                .country("DE").mode(WorkMode.ONSITE)
                .lane(StrategicClass.INTERNATIONAL_RELOCATION)
                .documents("/applications/anurag-camunda.pdf", "Three paragraphs.")
                .build();

        assertThat(value(derivations.derive(Concepts.RESUME_UPLOAD, withDocuments)))
                .isEqualTo("/applications/anurag-camunda.pdf");
        // The letter is drafted, so it is never filled without being read.
        Resolution letter =
                derivations.derive(Concepts.COVER_LETTER_TEXT, withDocuments).orElseThrow();
        assertThat(letter.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(letter.isAutoFillable()).isFalse();

        // Nothing prepared, nothing claimed.
        assertThat(derivations.derive(Concepts.RESUME_UPLOAD, Contexts.germanyOnsite()))
                .isEmpty();
    }

    @Test
    @DisplayName("every derivation names the context it read")
    void derivationsCarryEvidence() {
        Resolution resolution = resolve(Contexts.usRemoteFromIndia());
        assertThat(resolution.evidence()).isNotEmpty();
        assertThat(resolution.evidence())
                .anyMatch(e -> e.ref().equals("employment country"))
                .anyMatch(e -> e.ref().equals("job country"));
        assertThat(resolution.why()).contains("Confidence: HIGH");
    }

    @Test
    @DisplayName("sponsorship and authorisation are always opposite")
    void polarityIsAlwaysInverted() {
        // They are asked separately, in opposite polarity, and getting one right
        // while getting the other wrong is the classic version of this bug.
        List<ApplicationContext> all = List.of(
                Contexts.indiaOnsite(), Contexts.germanyOnsite(), Contexts.usOnsite(),
                Contexts.usRemoteFromIndia(), Contexts.usOnlyRemote(),
                Contexts.canadaRemoteFromIndia());
        for (ApplicationContext context : all) {
            assertThat(sponsorship(context))
                    .as("sponsorship for %s", context.describe())
                    .isNotEqualTo(authorisation(context));
        }
    }

    private static ApplicantProfile authorisedIn(Country... countries) {
        ApplicantProfile base = TestProfiles.indianApplicant();
        return new ApplicantProfile(base.name(), base.contact(), base.address(),
                new ApplicantProfile.WorkAuthorisation(List.of(countries), true, "note"),
                base.demographics(), base.compensation(), base.availability(),
                base.extraAnswers(), base.assistantBriefing());
    }
}
