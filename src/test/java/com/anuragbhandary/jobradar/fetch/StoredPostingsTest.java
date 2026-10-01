package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoredPostingsTest {

    private static final RawPosting STORED = new RawPosting("R-10064331", "Software Engineer, Backend",
            "Eindhoven, Netherlands", "Stored description", "https://nxp.example/job", LocalDate.of(2026, 9, 1));

    @Test
    @DisplayName("a stored posting is reused on six days of seven and read again on the seventh")
    void refreshesWeekly() {
        long refreshDays = LongStream.range(0, 7)
                .filter(day -> new StoredPostings.Known(Map.of(STORED.externalId(), STORED), day)
                        .reuse(STORED.externalId()) == null)
                .count();
        assertThat(refreshDays).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown id, or none, is never reused")
    void unknownIsRead() {
        StoredPostings.Known known = new StoredPostings.Known(Map.of(), 3);
        assertThat(known.reuse("R-1")).isNull();
        assertThat(known.reuse(null)).isNull();
        assertThat(StoredPostings.none().open(null, "x").reuse("R-1")).isNull();
    }

    @Test
    @DisplayName("Workday reuses a stored job instead of requesting its details")
    void workdayReusesStoredDetail() throws Exception {
        // A day on which this id is not due for a re-read.
        long day = LongStream.range(0, 7)
                .filter(d -> Math.floorMod(STORED.externalId().hashCode() + d, 7) != 0)
                .findFirst().orElseThrow();
        StoredPostings stored = new StoredPostings(null) {
            @Override
            public Known open(com.anuragbhandary.jobradar.domain.Source source, String boardToken) {
                return new Known(Map.of(STORED.externalId(), STORED), day);
            }
        };
        WorkdayFetcherTest.RecordingClient client = new WorkdayFetcherTest.RecordingClient();
        WorkdayFetcher fetcher = new WorkdayFetcher(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                com.anuragbhandary.jobradar.filter.RealConfigAccess.targetPlaces(),
                new com.anuragbhandary.jobradar.filter.TitleFilter(new com.anuragbhandary.jobradar.config.AppProperties(
                        null, null, null, null,
                        com.anuragbhandary.jobradar.filter.RealConfigAccess.screening(), null)),
                stored);

        FetchBatch batch = fetcher.fetch("nxp/wd3/careers");

        assertThat(batch.postings()).containsExactly(STORED);
        assertThat(client.detailed).isEmpty();
    }
}
