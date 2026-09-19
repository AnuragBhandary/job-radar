package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BondDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Selected candidates must sign a service bond of 2 years.",
            "There is a 2-year bond with the company.",
            "Freshers will be required to sign a bond of 18 months.",
            "Bond period: 1.5 years, bond amount Rs 2,00,000.",
            "A training cost recovery clause applies if you leave within a year.",
            "Candidates must be willing to sign an employment bond.",
            "Original certificates will be retained for the bond duration.",
            "A 3 year bond applies to all trainees.",
            "Joining bond of two years applicable.",
    })
    void findsAStatedBond(String text) {
        assertThat(BondDetector.find(text)).isPresent();
    }

    /** Every one of these is from the real corpus. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Postman is privately held, with funding from Battery Ventures, BOND, Coatue, CRV.",
            "A principal liquidity provider and market maker across ETFs, bonds, FX & Digital Assets.",
            "Regular in-person team events: we bond over vibrant events.",
            "Coordinate regular activities for employees to get to know each other and bond outside of work.",
            "Bond across Engineering, Product, Design, Marketing, Data Science and Business Operations.",
            "Across all Equity products including Delivery, Intraday, ETFs, IPOs, Baskets, Bonds, NPS, and SIPs.",
            "Ability to forge strong bonds, work collaboratively with internal partners.",
            "Negotiating enterprise-level cloud service agreements.",
            "Maintain trackers for lease expiries, security deposits, lock-in periods and notice periods.",
    })
    void ignoresEveryOtherBond(String text) {
        assertThat(BondDetector.find(text)).isEmpty();
    }
}
