package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The salary strings here are real, taken from the two Grafana Labs postings and
 * the two benefit lines that were misread as pay in the first run.
 */
class SignalExtractorTest {

    private final SignalExtractor extractor = new SignalExtractor();

    @Nested
    @DisplayName("sponsorship")
    class Sponsorship {

        @ParameterizedTest
        @ValueSource(strings = {
                "We are unable to sponsor visas for this position.",
                "This role does not offer sponsorship.",
                "No visa sponsorship is available for this opening.",
                "Candidates must already have the right to work in Ireland.",
                "Applicants require an existing right to work in the EU.",
                "This position requires an active security clearance.",
        })
        @DisplayName("phrases that answer the question with 'not you' are blocking")
        void detectsBlocking(String text) {
            assertThat(extractor.sponsorship(text)).startsWith("blocked:");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "A relocation package with visa support for those who need it.",
                "We offer relocation assistance to new employees.",
                "Visa sponsorship is available for this role.",
                "We sponsor candidates from outside the EU.",
        })
        @DisplayName("phrases that offer help are supportive")
        void detectsSupportive(String text) {
            assertThat(extractor.sponsorship(text)).startsWith("supportive:");
        }

        @Test
        @DisplayName("most postings say nothing, and silence is not a signal")
        void silenceIsNull() {
            assertThat(extractor.sponsorship(
                    "You will build backend services in Java and work with Kafka.")).isNull();
            assertThat(extractor.sponsorship(null)).isNull();
        }

        @Test
        @DisplayName("a blocking phrase wins over an encouraging one in the same posting")
        void blockingWins() {
            assertThat(extractor.sponsorship(
                    "We offer relocation assistance. We cannot sponsor work visas."))
                    .startsWith("blocked:");
        }
    }

    @Nested
    @DisplayName("salary")
    class Salary {

        @Test
        @DisplayName("a stated range is captured with enough clause to read it")
        void capturesAStatedRange() {
            assertThat(extractor.salary(
                    "In Ireland, the compensation range for this role is "
                            + "€81,600 - €97,920. Actual compensation may vary."))
                    .contains("81,600");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Annual learning & development stipend (€1,400 per year) and more.",
                "The Well program launches in 2026 and scales to €1,000 annually.",
        })
        @DisplayName("benefits quoted per year are not salaries")
        void rejectsBenefitAmounts(String text) {
            // Both of these reached the digest as pay. Nobody is offered EUR 1,400
            // a year, so magnitude separates them where the wording does not.
            assertThat(extractor.salary(text)).isNull();
        }

        @Test
        @DisplayName("a large figure with no pay word is not a salary either")
        void rejectsFundingAnnouncements() {
            assertThat(extractor.salary("We raised €40,000,000 in our Series B.")).isNull();
        }

        @Test
        @DisplayName("Indian postings quote lakhs")
        void capturesLakhs() {
            assertThat(extractor.salary("Compensation: 12 LPA plus benefits"))
                    .contains("12 LPA");
        }

        @Test
        @DisplayName("a posting that states nothing returns null")
        void silenceIsNull() {
            assertThat(extractor.salary("Great team, great mission.")).isNull();
            assertThat(extractor.salary(null)).isNull();
        }
    }
}
