package com.anuragbhandary.jobradar.sheets;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplicationRowTest {

    private static Posting posting(Integer minYears) {
        Posting p = new Posting(Source.GREENHOUSE, "stripe", "1", "Software Engineer, New Grad");
        p.setLocation("Dublin, Ireland");
        p.setUrl("https://example.com/1");
        p.setPostedDate(LocalDate.of(2026, 9, 1));
        p.setMinYears(minYears);
        return p;
    }

    @Test
    @DisplayName("cells come out in the spreadsheet's column order")
    void matchesSheetColumnOrder() {
        // The sheet predates this tool and is edited by hand. The tool fits the
        // sheet, not the other way round.
        List<Object> cells = ApplicationRow
                .from(posting(0), "Stripe", LocalDate.of(2026, 9, 6)).toCells();

        assertThat(cells).hasSize(ApplicationRow.COLUMNS.size());
        assertThat(cells.get(0)).isEqualTo("Stripe");
        assertThat(cells.get(1)).isEqualTo("Software Engineer, New Grad");
        assertThat(cells.get(2)).isEqualTo("Dublin, Ireland");
        assertThat(cells.get(3)).isEqualTo("2026-09-06");
        assertThat(cells.get(4)).isEqualTo("Applied");
        assertThat(cells.get(5)).isEqualTo("2026-09-01");
        assertThat(cells.get(7)).isEqualTo("https://example.com/1");
    }

    @Test
    @DisplayName("a missing years requirement is written out, not left blank")
    void writesNoneStatedExplicitly() {
        // A blank cell reads as "not filled in yet". That the posting stated no
        // requirement is a finding, and the column exists to record it.
        assertThat(ApplicationRow.from(posting(-1), "Adyen", LocalDate.now()).toCells().get(6))
                .isEqualTo("none stated");
        assertThat(ApplicationRow.from(posting(null), "Adyen", LocalDate.now()).toCells().get(6))
                .isEqualTo("none stated");
    }

    @Test
    @DisplayName("the experience wording distinguishes entry level from a stated minimum")
    void recordsExperienceWording() {
        assertThat(ApplicationRow.from(posting(0), "Stripe", LocalDate.now()).toCells().get(6))
                .isEqualTo("0 years / entry level");
        assertThat(ApplicationRow.from(posting(1), "Stripe", LocalDate.now()).toCells().get(6))
                .isEqualTo("1+ years");
    }

    @Test
    @DisplayName("null fields become empty cells, never the text 'null'")
    void nullsBecomeEmptyCells() {
        Posting p = new Posting(Source.LEVER, "cred", "1", "Backend Engineer");
        List<Object> cells = ApplicationRow.from(p, "CRED", LocalDate.now()).toCells();

        assertThat(cells).doesNotContain("null");
        assertThat(cells.get(2)).isEqualTo("");
    }

    @Test
    @DisplayName("a newly appended row is always Applied")
    void newRowsAreApplied() {
        assertThat(ApplicationRow.from(posting(0), "Stripe", LocalDate.now()).status())
                .isEqualTo("Applied");
    }
}
