package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.domain.Country;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A profile shaped like the real one, with nothing real in it. */
public final class TestProfiles {

    private TestProfiles() {
    }

    public static ApplicantProfile indianApplicant() {
        return new ApplicantProfile(
                new ApplicantProfile.Name("Ada", "M", "Lovelace", "Ada"),
                new ApplicantProfile.Contact(
                        "ada@example.com", "+91", "9876543210",
                        "https://linkedin.com/in/example", "https://github.com/example",
                        "https://example.github.io", "https://leetcode.com/example"),
                new ApplicantProfile.Address(
                        "1 Example Street", "Second Line", "Nagpur", "Maharashtra",
                        // Not a real postal code. The rest of this fixture is
                        // openly fictional and this one value was the applicant's
                        // actual PIN, sitting in a public repository.
                        "999999", "India", "Indian"),
                new ApplicantProfile.WorkAuthorisation(
                        List.of(Country.INDIA), true, "A sentence about sponsorship."),
                new ApplicantProfile.Demographics(
                        "Male", "Asian", "No", "I am not a protected veteran", "", ""),
                new ApplicantProfile.Compensation(
                        Map.of(
                                Country.INDIA, new ApplicantProfile.Compensation.Band(
                                        "INR", new BigDecimal("1200000"),
                                        new BigDecimal("1800000"), "per year"),
                                Country.GERMANY, new ApplicantProfile.Compensation.Band(
                                        "EUR", new BigDecimal("55000"),
                                        new BigDecimal("65000"), "per year")),
                        "Open on compensation.", false),
                new ApplicantProfile.Availability("None", "Immediately", true),
                List.of(
                        new ApplicantProfile.ExtraAnswer(
                                "how did you hear", "Company careers page"),
                        new ApplicantProfile.ExtraAnswer(
                                "are you at least 18", "Yes"),
                        // A match written the way the question is actually worded,
                        // punctuation and all - the case the Map version dropped.
                        new ApplicantProfile.ExtraAnswer(
                                "status that allows you to work",
                                "I am a citizen / permanent resident")),
                List.of("She is in Nagpur and needs no permit to work in India.",
                        "About one year of experience, none of it paid."));
    }
}
