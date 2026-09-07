package com.anuragbhandary.jobradar.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReplyClassifierTest {

    private final ReplyClassifier classifier = new ReplyClassifier();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Update on your application|We regret to inform you that we will not be moving forward|REJECTION",
        "Your application|Unfortunately you were unsuccessful on this occasion|REJECTION",
        "Thanks for applying|We have decided not to proceed with your application|REJECTION",
        "Application update|We will keep your CV on file for future roles|REJECTION",
        "Next steps|We would like to invite you to interview next week|INTERVIEW",
        "Chat?|Please book a time using my calendly.com link|INTERVIEW",
        "Your application|We are moving forward with your application|INTERVIEW",
        "Assessment|Please complete the online assessment within 5 days|ASSESSMENT",
        "Coding challenge|Your HackerRank test is ready|ASSESSMENT",
        "Congratulations|We are pleased to offer you the position|OFFER",
        "Received|Thank you for applying to our Backend Engineer role|ACKNOWLEDGEMENT",
        "Application received|We have received your application and are reviewing it|ACKNOWLEDGEMENT"
    })
    void readsTheCommonWordings(String subject, String body, ReplyKind expected) {
        assertThat(classifier.classify(subject, body)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------------
    // The trap: rejections are written to sound like near-misses, so they share
    // almost all their vocabulary with interview invitations.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("'not to invite you to the next stage' is a rejection, not an interview")
    void negationDecidesTheMeaning() {
        assertThat(classifier.classify("Update",
                "We have decided not to invite you to the next stage of the process."))
                .isEqualTo(ReplyKind.REJECTION);

        assertThat(classifier.classify("Update",
                "We would like to invite you to the next stage of the process."))
                .isEqualTo(ReplyKind.INTERVIEW);
    }

    @Test
    @DisplayName("'not be moving forward' beats 'moving forward'")
    void rejectionIsCheckedBeforeInterview() {
        assertThat(classifier.classify("Your application",
                "After careful consideration we will not be moving forward with your "
                        + "application at this time."))
                .isEqualTo(ReplyKind.REJECTION);
    }

    @Test
    @DisplayName("an acknowledgement describing the interview process is still an acknowledgement")
    void boilerplateAboutInterviewsIsNotAnInvitation() {
        assertThat(classifier.classify("We have received your application",
                "Thank you for applying. Our process involves a phone screen followed "
                        + "by a technical interview."))
                .isEqualTo(ReplyKind.ACKNOWLEDGEMENT);
    }

    @Test
    @DisplayName("job alerts never reach the matcher")
    void jobAlertsAreDiscarded() {
        // The noise problem: dozens a week, and they name the company, the role
        // and the word "application".
        assertThat(classifier.classify("Job alert: 12 new Backend Engineer roles",
                "New jobs matching your search at Stripe, N26 and Celonis"))
                .isEqualTo(ReplyKind.UNRELATED);
    }

    @Test
    void anythingElseIsUnrelated() {
        assertThat(classifier.classify("Your Amazon order has shipped", "Arriving Tuesday"))
                .isEqualTo(ReplyKind.UNRELATED);
        assertThat(classifier.classify(null, null)).isEqualTo(ReplyKind.UNRELATED);
    }

    @Test
    @DisplayName("only outcomes that change something carry a tracker status")
    void acknowledgementsChangeNothing() {
        assertThat(ReplyKind.ACKNOWLEDGEMENT.changesStatus()).isFalse();
        assertThat(ReplyKind.UNRELATED.changesStatus()).isFalse();
        assertThat(ReplyKind.REJECTION.trackerStatus()).isEqualTo("Rejected");
        assertThat(ReplyKind.OFFER.weight()).isGreaterThan(ReplyKind.INTERVIEW.weight());
    }
}
