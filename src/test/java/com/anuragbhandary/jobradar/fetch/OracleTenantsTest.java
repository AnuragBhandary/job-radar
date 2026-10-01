package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tokens and labels are real, from the boards discovery added on 2026-10-01. */
class OracleTenantsTest {

    @Test
    @DisplayName("every site, test pod and development pod of a tenant is one tenant")
    void tenant() {
        assertThat(OracleTenants.tenant("fa-ewjt-saasfaprod1/fa-ewjt-saasfaprod1.fa.ocs.oraclecloud.com/CX/1"))
                .isEqualTo("ewjt");
        assertThat(OracleTenants.tenant("fa-ewjt-dev1-saasfaprod1/fa-ewjt-dev1-saasfaprod1.fa.ocs.oraclecloud.com/CX_2/1"))
                .isEqualTo("ewjt");
        assertThat(OracleTenants.tenant("ecyq-test/ecyq-test.fa.em2.oraclecloud.com/CX_1/2")).isEqualTo("ecyq");
        assertThat(OracleTenants.tenant("egug/egug.fa.us2.oraclecloud.com/CX_1/3")).isEqualTo("egug");
        assertThat(OracleTenants.isNonProduction("ecyq-test/ecyq-test.fa.em2.oraclecloud.com/CX_1/2")).isTrue();
        assertThat(OracleTenants.isNonProduction("egug/egug.fa.us2.oraclecloud.com/CX_1/3")).isFalse();
    }

    @Test
    @DisplayName("the same requisition on two sites has one key; other sources have none")
    void requisitionKey() {
        assertThat(OracleTenants.requisitionKey(Source.ORACLE_HCM, "egug/egug.fa.us2.oraclecloud.com/CX/9", "26014499"))
                .isEqualTo(OracleTenants.requisitionKey(Source.ORACLE_HCM,
                        "egug/egug.fa.us2.oraclecloud.com/CX_1/9", "26014499"));
        assertThat(OracleTenants.requisitionKey(Source.GREENHOUSE, "alphasense", "1")).isEmpty();
    }

    @Test
    @DisplayName("site words come off a label")
    void clean() {
        assertThat(OracleTenants.clean("Cummins Talent Acquisition")).isEqualTo("Cummins");
        assertThat(OracleTenants.clean("Cummins Veterans")).isEqualTo("Cummins");
        assertThat(OracleTenants.clean("Chase- Candidate Experience page")).isEqualTo("Chase");
        assertThat(OracleTenants.clean("Opportunities at Ipsos")).isEqualTo("Ipsos");
        assertThat(OracleTenants.clean("at Marriott")).isEqualTo("Marriott");
        assertThat(OracleTenants.clean("WM Old Version")).isEqualTo("WM");
        assertThat(OracleTenants.clean("EXL Talent Acquisition Team")).isEqualTo("EXL");
        assertThat(OracleTenants.clean("Candidate Experience site")).isEmpty();
        assertThat(OracleTenants.clean("Alumni")).isEmpty();
        assertThat(OracleTenants.clean("Cummins RePower")).isEqualTo("Cummins RePower");
    }

    @Test
    @DisplayName("a site with no name of its own borrows the employer's from a sibling site")
    void plan() {
        BoardToken amexGeneric = board("egug/egug.fa.us2.oraclecloud.com/CX/300000000228786", "Candidate Experience site");
        BoardToken amex = board("egug/egug.fa.us2.oraclecloud.com/CX_1/300000000228786", "American Express");
        BoardToken exlCode = board("fa-ewjt-saasfaprod1/fa-ewjt-saasfaprod1.fa.ocs.oraclecloud.com/CX/1", "Fa Ewjt Saasfaprod1");
        BoardToken exl = board("fa-ewjt-saasfaprod1/fa-ewjt-saasfaprod1.fa.ocs.oraclecloud.com/CX_2/1", "EXL Talent Acquisition Team");
        BoardToken cummins = board("fa-espx-saasfaprod1/fa-espx-saasfaprod1.fa.ocs.oraclecloud.com/CX_1/5", "Cummins Veterans");
        BoardToken cumminsTa = board("fa-espx-saasfaprod1/fa-espx-saasfaprod1.fa.ocs.oraclecloud.com/CX_2/5", "Cummins Talent Acquisition");
        BoardToken cumminsCode = board("fa-espx-saasfaprod1/fa-espx-saasfaprod1.fa.ocs.oraclecloud.com/CX/5", "Fa Espx Saasfaprod1");
        BoardToken majesco = board("fa-emad-saasfaprod1/fa-emad-saasfaprod1.fa.ocs.oraclecloud.com/CX/7", "Candidate Experience site");

        Map<String, String> labels = OracleTenants.plan(List.of(
                        amexGeneric, amex, exlCode, exl, cummins, cumminsTa, cumminsCode, majesco))
                .relabel().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getToken(), Map.Entry::getValue));

        assertThat(labels).containsEntry(amexGeneric.getToken(), "American Express")
                .containsEntry(exlCode.getToken(), "EXL")
                .containsEntry(exl.getToken(), "EXL")
                .containsEntry(cummins.getToken(), "Cummins")
                .containsEntry(cumminsCode.getToken(), "Cummins")
                .doesNotContainKey(amex.getToken())
                // Nothing better to give it.
                .doesNotContainKey(majesco.getToken());
    }

    @Test
    @DisplayName("a name from the seed list beats the sites' own")
    void seededNameWins() {
        BoardToken seeded = board("jpmc/jpmc.fa.oraclecloud.com/CX_1001/300000000289360", "JPMorgan Chase");
        BoardToken chase1 = board("jpmc/jpmc.fa.oraclecloud.com/CX_1002/300000000289360", "Chase- Candidate Experience page");
        BoardToken chase2 = board("jpmc/jpmc.fa.oraclecloud.com/CX_1002/300000000289276", "Chase- Candidate Experience page");
        BoardToken alumni = board("jpmc/jpmc.fa.oraclecloud.com/CX_2001/300000000289360", "Alumni");

        Map<BoardToken, String> relabel = OracleTenants.plan(List.of(seeded, chase1, chase2, alumni),
                Map.of(seeded.getToken(), "JPMorgan Chase")).relabel();
        assertThat(relabel).doesNotContainKey(seeded)
                .containsEntry(alumni, "JPMorgan Chase")
                .containsEntry(chase1, "JPMorgan Chase")
                .containsEntry(chase2, "JPMorgan Chase");
    }

    @Test
    @DisplayName("a test copy is retired only when its live site is fetched")
    void retireCopies() {
        BoardToken live = board("ecyq/ecyq.fa.em2.oraclecloud.com/CX_1/300000000345142", "DNV");
        BoardToken copy = board("ecyq-test/ecyq-test.fa.em2.oraclecloud.com/CX_1/300000000345142", "DNV");
        BoardToken onlyCopy = board("ecum-test/ecum-test.fa.em2.oraclecloud.com/CX_2/300000000345058", "LBC ORC");

        Map<BoardToken, String> retire = OracleTenants.plan(List.of(live, copy, onlyCopy)).retire();
        assertThat(retire).containsOnlyKeys(copy);
    }

    private static BoardToken board(String token, String label) {
        BoardToken b = new BoardToken(Source.ORACLE_HCM, token, label);
        b.setActive(true);
        return b;
    }
}
