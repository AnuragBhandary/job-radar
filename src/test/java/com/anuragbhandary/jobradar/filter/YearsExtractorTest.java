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

    @Nested
    @DisplayName("the qualification line, wherever the posting puts it")
    class QualificationLineCases {

        @Test
        @DisplayName("Bosch writes it last, after the 'Good to Have' heading")
        void afterTheWishlistHeadingStillCounts() {
            // Both real, and both reached the 2026-09-16 digest as "none stated".
            assertThat(minYears("Must Have Skills: Strong proficiency in React, TypeScript. "
                    + "Good To Have: VSCode extension development on Azure. "
                    + "B.E 3 to 6 years experience")).isEqualTo(3);
            assertThat(minYears("Build System Development: modern CMake. Good to Have: "
                    + "Zephyr know how , MCU Know how BE, ME - Electronics background "
                    + "4 to 7 years")).isEqualTo(4);
        }

        @Test
        @DisplayName("Bosch's 'No. of years of Experience required' heading counts")
        void explicitExperienceHeading() {
            assertThat(minYears("Educational qualification: 5 No. of years of Experience "
                    + "required: 1 to 2 years of hands-on experience with hydraulic pumps"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a wishlist line is not a qualification, however it is worded")
        void aWishlistLineIsNotAQualification() {
            // Real. A first version of this rule anchored on the generic "N years
            // of experience" and rejected this posting on its nice-to-have line.
            assertThat(extractor.extract("Requirements: You write Go and enjoy platform work. "
                    + "NICE TO HAVES: - 2-4 years of experience as a Software Engineer")
                    .isNoneStated()).isTrue();
        }

        @Test
        @DisplayName("the qualification survives being written across lines")
        void lineBreaksDoNotHideIt() {
            // How the board actually stores it: a bulleted list, one item per line.
            assertThat(minYears("Must Have Skills:\n- React, TypeScript\nGood To Have:\n"
                    + "- Azure Cloud Foundation\nB.E\n3 to 6 years experience")).isEqualTo(3);
        }

        @Test
        @DisplayName("'degree, or N years of equivalent experience' is no requirement")
        void anAlternativeToADegreeAddsNothing() {
            // Google's phrasing: one or the other, and the degree is held.
            assertThat(extractor.extract("Good to Have: Bachelor's degree in Computer "
                    + "Science, or 4+ years of equivalent practical experience.")
                    .minYears()).isEqualTo(YearsExtraction.NONE_STATED);
        }

        @Test
        @DisplayName("'degree OR minimum N years' is an alternative too")
        void minimumYearsInsteadOfADegree() {
            assertThat(minYears("Requirements: A bachelor's degree OR minimum 4 years of "
                    + "experience with vocational education.")).isEqualTo(YearsExtraction.NONE_STATED);
        }

        @Test
        @DisplayName("years that come with a degree are still a bar")
        void yearsWithAMastersStillCount() {
            // The real posting. The Master's route is the shorter one, and it is real.
            assertThat(minYears("Requirements: Minimum 5 years of relevant experience with a "
                    + "Bachelor's degree, or 3 years with a Master's degree, in data handling."))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("'degree plus N years' is a bar, not an alternative")
        void degreePlusYearsIsARequirement() {
            assertThat(minYears("Requirements: Bachelor's degree in Computer Science, or a "
                    + "related field, plus 3 years of related work experience.")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("a ceiling is not a requirement")
    class UpperBounds {

        @Test
        @DisplayName("HackerRank L1: 'up to 3 years ... Strong freshers' states no minimum")
        void upToIsACeiling() {
            assertThat(minYears("Who you are: an early-career engineer with up to 3 years of "
                    + "relevant experience. Strong freshers are encouraged to apply."))
                    .isEqualTo(YearsExtraction.NONE_STATED);
        }

        @Test
        @DisplayName("a privacy footer keeping data 'for up to 2 years' is not experience")
        void privacyFooterIsIgnored() {
            assertThat(extractor.extract("Requirements: Python and SQL. Your data is kept for "
                    + "up to 2 years in our candidate pool.").minYears())
                    .isEqualTo(YearsExtraction.NONE_STATED);
        }

        @Test
        @DisplayName("a real minimum next to a ceiling still counts")
        void realMinimumStillCounts() {
            assertThat(minYears("Requirements: 2+ years of backend experience. Data is kept for "
                    + "up to 1 year.")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("an optional number is not the bar")
    class OptionalCases {

        @Test
        @DisplayName("Bosch 'SW developer :Java': the optional Product Owner years do not win")
        void optionalYearsAreIgnored() {
            // The real paragraph. It was recommended as a one-year role.
            assertThat(minYears("Experience: 3-5 years of professional experience in Java "
                    + "software development, with a focus on building enterprise-level "
                    + "applications optionally 1-2 years of experience or significant "
                    + "exposure to Product Owner responsibilities")).isEqualTo(3);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "2+ years of backend experience; ideally 1 year with Kafka",
                "Minimum 4 years in Python. Preferably 1+ years of Go.",
                "3 years building APIs, and ideally 1 year of Rust",
        })
        @DisplayName("hedge words before a smaller number leave the real requirement standing")
        void hedgedNumbersAreSkipped(String text) {
            assertThat(minYears(text)).isGreaterThan(1);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
                // Scale AI's heading for its requirements, not a softener.
                "Ideally you'd have: 4+ years of experience building high-performance systems | 4",
                // Philips: the only number in the posting, so it is the bar.
                "You're the right fit if: Preferably 6+ years of experience.                   | 6",
                // InMobi: the hedge belongs to the previous clause.
                "Master's preferred; a PhD is a plus At least 3 years of experience as an MLE | 3",
        })
        @DisplayName("a hedge never makes a posting read as stating nothing")
        void hedgeAloneIsStillTheBar(String text, int expected) {
            assertThat(minYears(text)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an unhedged small number still counts")
        void plainRequirementStillCounts() {
            assertThat(minYears("You have 1-3 years of experience and optionally a degree."))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("years stated in the title")
    class TitleCases {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
                "Site Reliability Engineer - AWS (4 to 8 Years) | 4",
                "Backend Engineer (5+ years)                    | 5",
                "Data Engineer, 3-5 yrs                         | 3",
                "Software Engineer (0-2 Years)                  | 0",
        })
        @DisplayName("a plural or range form is a requirement")
        void readsStatedYears(String title, int expected) {
            assertThat(extractor.extractFromTitle(title).minYears()).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Software Engineer, 2 Year Rotational Programme",
                "Graduate Engineer - 2 Years Rotational Program",
                "Engineer, 3 years fixed-term",
                "Software Engineer (12 month contract)",
                "SDE I",
        })
        @DisplayName("a duration is not a requirement")
        void ignoresDurations(String title) {
            // A false positive in a title rejects a whole role, so the singular
            // form and anything followed by a programme word are never read.
            assertThat(extractor.extractFromTitle(title).isNoneStated()).isTrue();
        }
    }

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

    @ParameterizedTest
    @ValueSource(strings = {
            "Experience (non-internship) in professional software development",
            "Experience in professional, non-internship software development",
            "Experience in professional, non-internship software development, or"
                    + " experience (non-internship) in professional software development",
    })
    @DisplayName("Amazon's SDE II wording is a requirement even with no number attached")
    void detectsNonInternshipWithoutANumber(String text) {
        // These three phrasings put 26 mid-level AWS roles into the candidate
        // list - 30% of it - because the old pattern needed "N years of" in front.
        assertThat(extractor.extract(text).hasNonInternshipRequirement()).isTrue();
    }

    @Test
    @DisplayName("a lower number in the preferred section does not become the requirement")
    void preferredSectionDoesNotUndercutTheRequirement() {
        // Amazon's Network Dev Engineer I in Bengaluru: required 2+, preferred 1+.
        // Taking the smallest number in the document read the bar as 1 and shipped
        // a two-year role as an entry-level candidate.
        String description = "- 2+ years of IT Security experience"
                + " - 2+ years of major internet routing protocols experience."
                + " The team runs the corporate network across the region and owns"
                + " its security posture from end to end, working with partners."
                + " Preferred: - 1+ years of automation scripting using Python,"
                + " Bash, Shell and/or Perl experience";
        assertThat(minYears(description)).isEqualTo(2);
    }

    @Test
    @DisplayName("a description opening on 'Preferred' is read whole, not as empty")
    void aHeadTooShortToBeRealIsNotASplit() {
        assertThat(minYears("Preferred qualifications: 5+ years of experience")).isEqualTo(5);
    }

}
