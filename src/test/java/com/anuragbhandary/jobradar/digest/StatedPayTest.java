package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatedPayTest {

    /** Planet Labs Berlin, 2026-09-28. */
    @Test
    void readsAEuropeanRange() {
        assertThat(SalaryFloorAdvisor.lowestStatedAmount(
                "bonuses and/or equity. Germany Salary Range €38.000-€47.500 EUR San Francisco", "EUR"))
                .isEqualByComparingTo(new BigDecimal("38000"));
    }

    @Test
    void readsCodesThousandsAndK() {
        assertThat(SalaryFloorAdvisor.lowestStatedAmount("EUR 52,000 - 60,000 EUR", "EUR"))
                .isEqualByComparingTo(new BigDecimal("52000"));
        assertThat(SalaryFloorAdvisor.lowestStatedAmount("€55k to €70k", "EUR"))
                .isEqualByComparingTo(new BigDecimal("55000"));
    }

    @Test
    void ignoresOtherCurrenciesAndMonthlyFigures() {
        assertThat(SalaryFloorAdvisor.lowestStatedAmount("$120,000 - $150,000 USD", "EUR")).isNull();
        assertThat(SalaryFloorAdvisor.lowestStatedAmount("€3.500 per month", "EUR")).isNull();
        assertThat(SalaryFloorAdvisor.lowestStatedAmount("2026 revenue grew 40%", "EUR")).isNull();
    }
}
