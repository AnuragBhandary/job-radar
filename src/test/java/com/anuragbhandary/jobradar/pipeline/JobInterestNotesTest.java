package com.anuragbhandary.jobradar.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobInterestNotesTest {

    @Test
    void addNoteKeepsHistoryAndSkipsARepeat() {
        JobInterest row = new JobInterest();
        row.addNote("Apply now. Target Bangalore");
        row.addNote("Apply now. Target Bangalore");
        row.addNote("2026-09-25: posting no longer available");
        assertThat(row.getNotes())
                .isEqualTo("Apply now. Target Bangalore\n2026-09-25: posting no longer available");
        assertThat(row.latestNote()).isEqualTo("2026-09-25: posting no longer available");
    }

    @Test
    void latestNoteOfNothingIsEmpty() {
        assertThat(JobInterest.latestNote(null)).isEmpty();
        assertThat(JobInterest.latestNote("\n  \n")).isEmpty();
    }
}
