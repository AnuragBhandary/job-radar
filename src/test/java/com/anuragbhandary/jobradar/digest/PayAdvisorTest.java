package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.YearsExtraction;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Expected pay and the ask, against the shipped pay table and country floors. */
class PayAdvisorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private final PayAdvisor advisor =
            new PayAdvisor(RealConfigAccess.pay(), RealConfigAccess.countryStrategy());

    private static Posting posting(Source source, String token, String title, String country,
            StrategicClass lane, Integer minYears) {
        Posting p = new Posting(source, token, "1", title);
        p.setCountryCode(country);
        p.setStrategicClass(lane);
        p.setMinYears(minYears);
        return p;
    }

    private String line(Posting p) {
        PayAdvisor.Pay pay = advisor.payFor(p);
        return pay == null ? null : advisor.format(pay, TODAY);
    }

    @Test
    void wideStatedRangeAsksAThirdOfTheWayUpNotTheBottom() {
        // Microsoft's Fast Track posting, 2026-10-19.
        Posting p = posting(Source.EIGHTFOLD, "microsoft/x/microsoft.com", "Fast Track Full Stack Engineer",
                "GB", StrategicClass.INTERNATIONAL_RELOCATION, YearsExtraction.NONE_STATED);
        p.setSalaryText("range for this role across United Kingdom is £ 70,300.00 - £ 133,900.00 per year.");

        PayAdvisor.Pay pay = advisor.payFor(p);

        assertThat(pay.stated()).isTrue();
        assertThat(pay.low()).isEqualTo(70_300);
        assertThat(pay.high()).isEqualTo(133_900);
        assertThat(pay.ask()).isEqualTo(89_000);
        assertThat(line(p)).startsWith("GBP 70,300-133,900/yr (stated) · ask GBP 89,000/yr (about Rs ");
    }

    @Test
    void narrowRangeAsksTheMiddleAndAWideOneAThirdUp() {
        assertThat(PayAdvisor.ask(50_000, 60_000, "GBP")).isEqualTo(55_000);
        assertThat(PayAdvisor.ask(600_000, 1_000_000, "INR")).isEqualTo(700_000);
    }

    @Test
    void lakhRangeReadsBothEnds() {
        Posting p = posting(Source.GREENHOUSE, "acme", "Backend Engineer", "IN",
                StrategicClass.INDIA_OTHER, 0);
        p.setSalaryText("CTC: 8-12 LPA plus benefits");

        PayAdvisor.Pay pay = advisor.payFor(p);

        assertThat(pay.low()).isEqualTo(800_000);
        assertThat(pay.high()).isEqualTo(1_200_000);
        assertThat(line(p)).isEqualTo("Rs 8-12 LPA (stated) · ask Rs 9 LPA");
    }

    @Test
    void monthlyStatedPayIsScaledToAYear() {
        Posting p = posting(Source.GREENHOUSE, "acme", "Data Engineer", "DE",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);
        p.setSalaryText("salary of €4.500 per month gross");

        assertThat(advisor.payFor(p).low()).isEqualTo(54_000);
    }

    @Test
    void hourlyPayIsNotReadAsAYear() {
        Posting p = posting(Source.GREENHOUSE, "acme", "Software Engineer", "GB",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);
        p.setSalaryText("pay £25 per hour");

        assertThat(advisor.payFor(p).stated()).isFalse();
    }

    @Test
    void citiIsABankNotBigTechEvenThoughScreeningListsIt() {
        Posting p = posting(Source.WORKDAY, "citi/wd5/2/india", "Java Developer", "IN",
                StrategicClass.INDIA_OTHER, 0);

        assertThat(line(p)).isEqualTo(
                "Rs 9-16 LPA (estimate: bank, entry, Software / Backend, +-25%) · ask Rs 11 LPA");
    }

    @Test
    void serviceFirmExperiencedQaIsScaledDown() {
        Posting p = posting(Source.WORKDAY, "accenture/wd103/AccentureCareers", "Test Automation Engineer",
                "IN", StrategicClass.INDIA_OTHER, 1);

        PayAdvisor.Pay pay = advisor.payFor(p);

        // 3.5-6.5 L, times 0.8 for QA and 1.2 for a stated year.
        assertThat(pay.low()).isEqualTo(336_000);
        assertThat(pay.high()).isEqualTo(624_000);
        assertThat(pay.basis()).isEqualTo("estimate: service, 1+ yrs, QA / Test Automation");
    }

    @Test
    void bigTechByItsSource() {
        Posting p = posting(Source.AMAZON, "IND", "System Development Engineer I", "IN",
                StrategicClass.INDIA_OTHER, 0);

        assertThat(advisor.payFor(p).basis()).startsWith("estimate: big tech");
    }

    @Test
    void indianInternshipIsAMonthlyStipend() {
        Posting p = posting(Source.GREENHOUSE, "cloudsek", "SDE Intern - Backend", "IN",
                StrategicClass.INDIA_OTHER, YearsExtraction.NONE_STATED);

        assertThat(line(p)).isEqualTo(
                "Rs 15,000-40,000/month (estimate: standard, intern, Software / Backend, +-25%)"
                        + " · ask Rs 23,000/month");
    }

    @Test
    void londonDataScientistGetsTheCityAndNoAnalystDiscount() {
        Posting p = posting(Source.ASHBY, "teya", "Data Scientist", "GB",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);
        p.setLocation("London");

        PayAdvisor.Pay pay = advisor.payFor(p);

        // 38-52k times 1.15 for London: 43,700-59,800, narrow, so the middle.
        assertThat(pay.low()).isEqualTo(43_700);
        assertThat(pay.high()).isEqualTo(59_800);
        assertThat(pay.ask()).isEqualTo(52_000);
        assertThat(pay.basis()).isEqualTo("estimate: standard, entry, Data Science / Analytics, london");
    }

    @Test
    void ukEstimateIsRaisedToTheVisaFloor() {
        Posting p = posting(Source.GREENHOUSE, "valeofoods", "Master Data Analyst", "GB",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);

        PayAdvisor.Pay pay = advisor.payFor(p);

        // 38-52k times 0.85 for analytics is under the 41,700 Skilled Worker floor.
        assertThat(pay.raisedToFloor()).isTrue();
        assertThat(pay.ask()).isEqualTo(42_000);
        assertThat(line(p)).endsWith(", raised to the visa floor");
    }

    @Test
    void remoteIntoIndiaUsesTheRemoteRow() {
        Posting p = posting(Source.ASHBY, "supabase", "SDK Engineer - Python", null,
                StrategicClass.INTERNATIONAL_REMOTE, YearsExtraction.NONE_STATED);

        assertThat(line(p)).startsWith("Rs 10-20 LPA (estimate: standard, entry");
    }

    @Test
    void noRowNoLine() {
        Posting p = posting(Source.GREENHOUSE, "tamara", "Analyst", "SA",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);

        assertThat(advisor.payFor(p)).isNull();
    }

    @Test
    void anAggregatorsCompanyMatchesOnAWholeWordOnly() {
        Posting hubspot = posting(Source.ARBEITNOW, "arbeitnow", "Engineer @ HubSpot", "IE",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);
        Posting ubs = posting(Source.ARBEITNOW, "arbeitnow", "Engineer @ UBS", "IE",
                StrategicClass.INTERNATIONAL_RELOCATION, 0);

        assertThat(advisor.tier(hubspot)).isEqualTo("standard");
        assertThat(advisor.tier(ubs)).isEqualTo("bank");
    }

    @Test
    void lakhsDropATrailingZero() {
        assertThat(PayAdvisor.lakhs(900_000)).isEqualTo("9");
        assertThat(PayAdvisor.lakhs(1_250_000)).isEqualTo("12.5");
    }
}
