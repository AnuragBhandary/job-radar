package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostingMapperTest {

    private final PostingMapper mapper = new PostingMapper();

    private static RawPosting raw(String description) {
        return new RawPosting("123", "Software Engineer", "Dublin, Ireland",
                description, "https://example.com/123", LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("the hash ignores whitespace reformatting")
    void hashIgnoresWhitespace() {
        assertThat(mapper.hash("1-3 years  of\n experience"))
                .isEqualTo(mapper.hash("1-3 years of experience"));
    }

    @Test
    @DisplayName("the hash notices a changed experience requirement")
    void hashNoticesRealEdits() {
        // The exact edit this tool exists to catch. Any normalisation aggressive
        // enough to hide it would defeat the purpose.
        assertThat(mapper.hash("2 years of experience"))
                .isNotEqualTo(mapper.hash("2+ years of experience"));
    }

    @Test
    @DisplayName("a null description hashes to null, not to the hash of empty")
    void nullDescriptionIsDistinguishable() {
        assertThat(mapper.hash(null)).isNull();
        assertThat(mapper.hash("")).isNotNull();
    }

    @Test
    @DisplayName("a new posting starts unscreened with firstSeen set")
    void buildsNewPosting() {
        Instant now = Instant.parse("2026-09-06T02:00:00Z");
        Posting p = mapper.toNewPosting(Source.GREENHOUSE, "stripe", raw("hello"), now);

        assertThat(p.getSource()).isEqualTo(Source.GREENHOUSE);
        assertThat(p.getBoardToken()).isEqualTo("stripe");
        assertThat(p.getExternalId()).isEqualTo("123");
        assertThat(p.getFirstSeen()).isEqualTo(now);
        assertThat(p.getLastSeen()).isEqualTo(now);
        assertThat(p.getVerdict()).isEqualTo(Verdict.UNSCREENED);
        assertThat(p.getDescriptionHash()).hasSize(64);
    }

    @Test
    @DisplayName("re-fetching moves lastSeen but never firstSeen")
    void refetchPreservesFirstSeen() {
        Instant first = Instant.parse("2026-09-01T02:00:00Z");
        Instant later = Instant.parse("2026-09-06T02:00:00Z");

        Posting p = mapper.toNewPosting(Source.GREENHOUSE, "stripe", raw("v1"), first);
        mapper.applyFields(p, raw("v2 - now with different text"), later);

        // firstSeen answers "how long has this been open?", which a re-fetch
        // cannot recover once overwritten.
        assertThat(p.getFirstSeen()).isEqualTo(first);
        assertThat(p.getLastSeen()).isEqualTo(later);
        assertThat(p.getDescriptionText()).isEqualTo("v2 - now with different text");
    }
}
