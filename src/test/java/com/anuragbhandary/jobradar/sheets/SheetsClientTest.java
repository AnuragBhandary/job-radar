package com.anuragbhandary.jobradar.sheets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SheetsClientTest {

    @Test
    @DisplayName("the written row number is read back out of the response range")
    void parsesUpdatedRange() {
        assertThat(SheetsClient.parseRow("'Untitled'!A42:I42")).isEqualTo(42);
        assertThat(SheetsClient.parseRow("Sheet1!A7:I7")).isEqualTo(7);
        assertThat(SheetsClient.parseRow("A7:I7")).isEqualTo(7);
        assertThat(SheetsClient.parseRow(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("the header row is located by content, not assumed")
    void findsHeaderRowByContent() {
        // Documented as row 6; found at row 7 on the live sheet, because a line
        // had been added to the instructions above it. Assuming the offset makes
        // the header read as an application.
        java.util.List<java.util.List<Object>> sheet = java.util.List.of(
                java.util.List.of("HOW TO USE"),
                java.util.List.of(""),
                java.util.List.of(""),
                java.util.List.of(""),
                java.util.List.of(""),
                java.util.List.of(""),
                java.util.List.<Object>of("Company", "Role", "Country/City"),
                java.util.List.<Object>of("Stripe", "Software Engineer, New Grad"));

        assertThat(SheetsClient.findFirstDataRow(sheet)).isEqualTo(8);
    }

    @Test
    @DisplayName("without a header the fallback keeps writes below the instructions")
    void fallsBackWhenHeaderMissing() {
        assertThat(SheetsClient.findFirstDataRow(java.util.List.of())).isEqualTo(7);
    }

    @Test
    @DisplayName("a status update cannot touch the header or the HOW TO USE block")
    void refusesToWriteAboveTheData() {
        // Rows 1-5 are instructions the user wrote and row 6 is the header.
        // Overwriting either destroys something that exists nowhere else.
        SheetsClient client = new SheetsClient(null,
                new com.anuragbhandary.jobradar.config.AppProperties(
                        new com.anuragbhandary.jobradar.config.AppProperties.Google(
                                null, "sheet-id", "Untitled"),
                        null, null, null, null, null));

        assertThatThrownBy(() -> client.updateStatus(6, "Rejected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data starts at row 7");
        assertThatThrownBy(() -> client.updateStatus(1, "Rejected"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unconfigured client reports no applied companies rather than failing")
    void degradesWhenUnconfigured() {
        // Losing the tracker should cost the digest its suppression, not its
        // existence.
        SheetsClient client = new SheetsClient(null,
                new com.anuragbhandary.jobradar.config.AppProperties(
                        new com.anuragbhandary.jobradar.config.AppProperties.Google(
                                null, "", "Untitled"),
                        null, null, null, null, null));

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.appliedCompanies()).isEmpty();
    }
}
