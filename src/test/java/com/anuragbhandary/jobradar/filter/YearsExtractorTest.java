package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The strings here are real. Every phrasing in {@link #realPhrasings} was taken
 * from the corpus of 5,893 postings fetched in Milestone 2, in rough order of
 * how often it occurs.
 */
class YearsExtractorTest {

    private final YearsExtractor extractor = new YearsExtractor();

    private int minYears(String text) {
        return extractor.extract(text).minYears();
    }

    @Nested
    @DisplayName("the cases the profile turns on")
    class ProfileCases {

        @Test
        @DisplayName("'1-3 years of hands-on software engineering experience' is eligible")
        void acceptsOneToThree() {
            assertThat(minYears("1-3 years of hands-on software engineering experience"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("'1 to 4 yrs' is eligible")
        void acceptsOneToFour() {
            assertThat(minYears("Looking for 1 to 4 yrs in backend development")).isEqualTo(1);
        }

        @Test
        @DisplayName("'5 years of experience developing large-scale applications' is not")
        void rejectsFiveYears() {
            // The Goldman "Analyst" requisition that looked like a graduate role.
            assertThat(minYears("5 years of experience developing large-scale applications,"
                    + " ideally in C++ and Java")).isEqualTo(5);
        }

        @Test
        @DisplayName("Amazon's non-internship wording is caught separately")
        void detectsNonInternshipRequirement() {
            YearsExtraction result = extractor.extract(
                    "1+ years of non-internship professional software development experience");
            assertThat(result.hasNonInternshipRequirement()).isTrue();
            assertThat(result.nonInternship()).contains("non-internship");
        }

        @Test
        @DisplayName("'non-internship' with no number attached is not a requirement")
        void ignoresNonInternshipWithoutYears() {
            assertThat(extractor.extract("Internship and non-internship candidates welcome")
                    .hasNonInternshipRequirement()).isFalse();
        }

        @Test
        @DisplayName("text with no years at all returns -1, not 0")
        void noYearsStatedIsMinusOne() {
            // -1 must not collapse into 0. "States no requirement" is a posting a
            // human has to read; "requires zero years" is one that is already fine.
            assertThat(minYears("We are looking for a curious engineer to join us."))
                    .isEqualTo(YearsExtraction.NONE_STATED);
            assertThat(minYears(null)).isEqualTo(YearsExtraction.NONE_STATED);
            assertThat(minYears("")).isEqualTo(YearsExtraction.NONE_STATED);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "3+ years of experience in a technical role          | 3",
            "5 years of experience in solution engineering       | 5",
            "5-8 years experience in an IT Operations role       | 5",
            "3–4 years in deal desk operations                   | 3",
            "4 to 8 years of experience in finance               | 4",
            "2-12+ years of industry software engineering        | 2",
            "6 - 10 years of full-cycle recruiting               | 6",
            "5 + years experience working with data              | 5",
            "1 year of experience with designing systems         | 1",
            "at least 1+ year of experience in Quality           | 1",
            "Long-term (3+ yrs) work experience                  | 3",
            "0 to 2+ years experience managing facilities        | 0",
    })
    @DisplayName("real phrasings from the corpus parse to their lower bound")
    void realPhrasings(String text, int expected) {
        assertThat(minYears(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a hyphenated duration is not an experience requirement")
    void ignoresProgrammeDurations() {
        // Celonis advertises a graduate fast-track as a "2-year development
        // programme". Reading that as two years of required experience would
        // reject exactly the kind of role being searched for.
        assertThat(minYears("The Celonis Lunar programme is a fast-track, 2-year"
                + " development programme designed for recent graduates"))
                .isEqualTo(YearsExtraction.NONE_STATED);
    }

    @Test
    @DisplayName("the smallest stated number wins")
    void takesTheMinimum() {
        assertThat(minYears("8+ years of leadership. 2 years of Python.")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "the first database provider to IPO in over 20 years",
            "we have been building this for 25 years",
    })
    @DisplayName("implausibly large numbers are company history, not requirements")
    void ignoresImplausibleYears(String text) {
        assertThat(minYears(text)).isEqualTo(YearsExtraction.NONE_STATED);
    }

    @Test
    @DisplayName("the matched phrase is kept so the rejection can be argued with")
    void recordsEvidence() {
        YearsExtraction result = extractor.extract(
                "Requirements: 7+ years of experience building distributed systems.");
        assertThat(result.minYears()).isEqualTo(7);
        assertThat(result.evidence()).contains("7+ years");
    }

    @Test
    @DisplayName("a range does not also match its own upper bound")
    void rangeUpperBoundIsNotASeparateMatch() {
        // If "5-8 years" matched twice, the second match would be "8 years" and
        // the minimum would still be 5 - but on "8-2 years" it would be wrong.
        assertThat(minYears("5-8 years of experience")).isEqualTo(5);
    }
}
