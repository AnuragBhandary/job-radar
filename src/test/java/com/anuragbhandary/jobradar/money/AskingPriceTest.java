package com.anuragbhandary.jobradar.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The figures that go in a salary box, and the ones that only orient the reader. */
class AskingPriceTest {

    private static AskingPrice euros() {
        return new AskingPrice("EUR", new BigDecimal("55000"), new BigDecimal("65000"),
                new BigDecimal("100"), "8 September 2026");
    }

    private static AskingPrice rupees() {
        return new AskingPrice("INR", new BigDecimal("1800000"), new BigDecimal("3000000"),
                null, "8 September 2026");
    }

    @Test
    @DisplayName("a foreign salary keeps its own currency and grouping")
    void foreignAnnual() {
        assertThat(euros().annualRange()).isEqualTo("EUR 55,000 - 65,000");
        assertThat(euros().monthlyRange()).isEqualTo("EUR 4,583 - 5,417");
    }

    @Test
    @DisplayName("a rupee salary is shown in lakhs, not with Western thousands")
    void rupeesReadAsRupees() {
        // "INR 1,800,000" is a figure an Indian reader has to stop and count.
        assertThat(rupees().annualRange()).isEqualTo("Rs 18L - 30L");
        assertThat(rupees().annualRange()).doesNotContain("1,800,000");
    }

    @Test
    @DisplayName("a rupee monthly figure uses Indian grouping")
    void indianGrouping() {
        assertThat(rupees().monthlyRange()).isEqualTo("Rs 1,50,000 - 2,50,000");
    }

    @Test
    @DisplayName("euros convert to lakhs at the configured rate")
    void conversion() {
        assertThat(euros().convertible()).isTrue();
        assertThat(euros().annualInRupees()).isEqualTo("Rs 55L - 65L");
        assertThat(euros().monthlyInRupees()).isEqualTo("Rs 4,58,333 - 5,41,667");
    }

    @Test
    @DisplayName("with no rate there is no conversion, and nothing is invented")
    void noRate() {
        AskingPrice noRate = new AskingPrice("EUR", new BigDecimal("55000"),
                new BigDecimal("65000"), null, "never");

        assertThat(noRate.convertible()).isFalse();
        assertThat(noRate.annualRange()).isEqualTo("EUR 55,000 - 65,000");
    }

    @Test
    @DisplayName("a crore is written as a crore")
    void crore() {
        AskingPrice big = new AskingPrice("INR", new BigDecimal("12000000"),
                new BigDecimal("25000000"), null, "today");

        assertThat(big.annualRange()).isEqualTo("Rs 1.2Cr - 2.5Cr");
    }
}
