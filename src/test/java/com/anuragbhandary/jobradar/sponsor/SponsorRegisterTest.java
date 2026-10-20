package com.anuragbhandary.jobradar.sponsor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Names are real rows from the 2026-10-20 UK and Dutch registers. */
class SponsorRegisterTest {

    private static SponsorRegister register(Path dir) throws Exception {
        Files.write(dir.resolve("gb.txt"), List.of(
                "Palantir Technologies UK Limited", "AGILENT TECHNOLOGIES LDA UK LIMITED",
                "JPMorgan Chase Bank, N.A.", "PA Consulting Services Limited",
                "WNS Global Services (UK) Ltd", "Fospha Limited", "SCA Investments t/a Gousto",
                "J Sainsbury Plc", "Quantum 21 Ltd T/A PlayMeTech", "CHECKOUT LTD"));
        Files.write(dir.resolve("nl.txt"), List.of("Mollie B.V."));
        return new SponsorRegister(dir.toString());
    }

    @Test
    void boardNamesFindTheirRegisteredCompany(@TempDir Path dir) throws Exception {
        SponsorRegister r = register(dir);
        assertThat(r.check("GB", "Palantir").orElseThrow().status()).isEqualTo(SponsorRegister.Status.LICENSED);
        assertThat(r.check("GB", "Agilent").orElseThrow().registeredName())
                .isEqualTo("AGILENT TECHNOLOGIES LDA UK LIMITED");
        assertThat(r.check("GB", "JPMorgan Chase").orElseThrow().status())
                .isEqualTo(SponsorRegister.Status.LICENSED);
        // SmartRecruiters tokens run the words together and add a number.
        assertThat(r.check("GB", "PAConsulting").orElseThrow().status())
                .isEqualTo(SponsorRegister.Status.LICENSED);
        assertThat(r.check("GB", "WNSGlobalServices144").orElseThrow().status())
                .isEqualTo(SponsorRegister.Status.LICENSED);
        assertThat(r.check("NL", "Mollie").orElseThrow().status()).isEqualTo(SponsorRegister.Status.LICENSED);
        // A trading name, a leading initial with a possessive, and a ".com".
        assertThat(r.check("GB", "Gousto1").orElseThrow().status()).isEqualTo(SponsorRegister.Status.LICENSED);
        assertThat(r.check("GB", "Sainsbury's").orElseThrow().status()).isEqualTo(SponsorRegister.Status.LICENSED);
        assertThat(r.check("GB", "Checkout.com").orElseThrow().status()).isEqualTo(SponsorRegister.Status.LICENSED);
    }

    @Test
    void aNameThatOnlyStartsARegisteredOneIsPossible(@TempDir Path dir) throws Exception {
        SponsorRegister.Match m = register(dir).check("GB", "Quantum").orElseThrow();
        assertThat(m.status()).isEqualTo(SponsorRegister.Status.POSSIBLE);
        assertThat(m.registeredName()).isEqualTo("Quantum 21 Ltd T/A PlayMeTech");
    }

    @Test
    void anEmployerMissingFromTheRegisterIsNotListed(@TempDir Path dir) throws Exception {
        SponsorRegister r = register(dir);
        assertThat(r.check("GB", "Standard Life").orElseThrow().status()).isEqualTo(SponsorRegister.Status.NOT_LISTED);
        // A whole word is needed: "Fos" is not Fospha.
        assertThat(r.check("GB", "Fos Ltd").orElseThrow().status()).isEqualTo(SponsorRegister.Status.NOT_LISTED);
    }

    @Test
    void aCareersSiteNameProvesNothing(@TempDir Path dir) throws Exception {
        assertThat(register(dir).check("GB", "Candidate Experience site").orElseThrow().status())
                .isEqualTo(SponsorRegister.Status.UNCLEAR_NAME);
    }

    @Test
    void countriesWithoutARegisterAreNotChecked(@TempDir Path dir) throws Exception {
        SponsorRegister r = register(dir);
        assertThat(r.check("DE", "Palantir")).isEmpty();
        assertThat(r.check("IN", "Palantir")).isEmpty();
        assertThat(new SponsorRegister(dir.resolve("absent").toString()).check("GB", "Palantir")
                .orElseThrow().status()).isEqualTo(SponsorRegister.Status.NO_REGISTER);
    }

    @Test
    void readsOnlySkilledWorkerRowsFromTheUkCsv() {
        String csv = """
                Organisation Name,Town/City,County,Type & Rating,Route
                Palantir UK Limited,London,,Worker (A rating),Skilled Worker
                Palantir UK Limited,London,,Worker (A rating),Global Business Mobility: Senior or Specialist Worker
                "Smith, Jones & Co",Leeds,,Worker (A rating),Skilled Worker
                Some Charity,York,,Temporary Worker (A rating),Charity Worker
                """;
        assertThat(SponsorRegister.skilledWorkerNames(csv))
                .containsExactly("Palantir UK Limited", "Smith, Jones & Co");
    }

    @Test
    void readsRowHeadersFromTheIndPage() {
        String html = "<tr><th scope=\"col\">Organisation</th></tr>"
                + "<tr><th scope=\"row\">\"\"Aa-Dee\"\" Machinefabriek B.V.</th><td>16051874</td></tr>"
                + "<tr><th scope=\"row\">Mollie B.V.</th><td>30204462</td></tr>";
        assertThat(SponsorRegister.recognisedSponsorNames(html))
                .containsExactly("\"Aa-Dee\" Machinefabriek B.V.", "Mollie B.V.");
    }
}
