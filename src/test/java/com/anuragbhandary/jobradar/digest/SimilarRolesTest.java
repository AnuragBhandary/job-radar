package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Visa's three Bengaluru openings on 2026-10-07. */
class SimilarRolesTest {

    private static Digest.Entry entry(String externalId, String title) {
        Posting p = new Posting(Source.WORKDAY, "visa/wd5/Visa", externalId, title);
        p.setCountryCode("IN");
        return new Digest.Entry(p, false, false);
    }

    @Test
    void foldsTitlesThatDifferOnlyInBrackets() {
        List<Digest.Entry> grouped = DigestService.grouped(List.of(
                entry("1", "Data Engineer"),
                entry("2", "Software Engineer (1-2 years of exp as Agentic AI Developer, Python)"),
                entry("3", "Data Engineer (1 - 2 years of experience in Java / Python / Go, Kafka, Flink, Hadoop)")),
                Map.of("visa/wd5/Visa", "Visa"));

        assertThat(grouped).hasSize(2);
        assertThat(grouped.getFirst().posting().getTitle()).isEqualTo("Data Engineer");
        assertThat(grouped.getFirst().similar()).singleElement()
                .satisfies(s -> assertThat(s.posting().getExternalId()).isEqualTo("3"));
        assertThat(grouped.get(1).similar()).isEmpty();
    }

    @Test
    void aShortTitleIsOnlySimilarInTheSameCountry() {
        Digest.Entry india = entry("1", "Data Engineer");
        Digest.Entry ireland = entry("2", "Data Engineer (Dublin)");
        ireland.posting().setCountryCode("IE");

        assertThat(DigestService.grouped(List.of(india, ireland), Map.of())).hasSize(2);
    }

    @Test
    void theBestOfEachCategoryComeFirstThenTheRestByScore() {
        List<Digest.Entry> byScore = List.of(
                entry("1", "Backend Engineer"), entry("2", "Software Engineer II"),
                entry("3", "Java Developer"), entry("4", "Data Engineer"), entry("5", "QA Engineer"));

        List<Digest.Entry> out = DigestService.featuredFirst(byScore, 1);

        assertThat(out).extracting(e -> e.posting().getExternalId())
                .containsExactly("1", "4", "5", "2", "3");
        assertThat(out).extracting(Digest.Entry::featured)
                .containsExactly(true, true, true, false, false);
    }

    @Test
    void indiaAndAbroadEachGetTheirOwnBestOf() {
        Digest.Entry india = entry("1", "Backend Engineer");
        india.posting().setStrategicClass(com.anuragbhandary.jobradar.domain.StrategicClass.INDIA_OTHER);
        Digest.Entry indiaToo = entry("2", "Software Engineer");
        indiaToo.posting().setStrategicClass(com.anuragbhandary.jobradar.domain.StrategicClass.INDIA_HOME);
        Digest.Entry berlin = entry("3", "Backend Engineer (Berlin)");
        berlin.posting().setStrategicClass(
                com.anuragbhandary.jobradar.domain.StrategicClass.INTERNATIONAL_RELOCATION);

        List<Digest.Entry> out = DigestService.featuredFirst(List.of(india, indiaToo, berlin), 1);

        // Berlin scores lowest but still leads the abroad set, ahead of India's second.
        assertThat(out).extracting(e -> e.posting().getExternalId()).containsExactly("1", "3", "2");
        assertThat(out).extracting(Digest.Entry::featured).containsExactly(true, true, false);
    }

    @Test
    void oneEmployerUnderItsTrackerAndBoardNamesIsOneKey() {
        assertThat(DigestService.companyKey("ScalableGmbH")).isEqualTo(DigestService.companyKey("Scalable GmbH"));
        assertThat(DigestService.companyKey("Gousto1")).isEqualTo("gousto");
    }
}
