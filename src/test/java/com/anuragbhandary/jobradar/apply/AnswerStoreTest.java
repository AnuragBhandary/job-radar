package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing to the profile, which is the only file in the project holding personal
 * data and is maintained by hand.
 */
class AnswerStoreTest {

    @TempDir
    Path dir;

    private Path profile(String body) throws IOException {
        Path file = dir.resolve("applicant.yml");
        Files.writeString(file, body);
        return file;
    }

    private static final String SKELETON = """
            job-radar:
              applicant:
                extra-answers:
                  - match: "how did you hear"
                    answer: "Company careers page"
            """;

    @Test
    @DisplayName("an answer is usable immediately and lands in the file")
    void remembersAndWrites() throws IOException {
        Path file = profile(SKELETON);
        AnswerStore store = new AnswerStore(TestProfiles.indianApplicant(), file);

        String said = store.remember("do you need sponsorship", "Yes, I need sponsorship");

        assertThat(said).contains("Written to applicant.yml").contains("without a restart");
        assertThat(store.answers("do you need sponsorship")).isTrue();
        assertThat(Files.readString(file)).contains("Yes, I need sponsorship");
    }

    @Test
    @DisplayName("the new answer sits above the old ones, because first match wins")
    void newestFirst() throws IOException {
        AnswerStore store = new AnswerStore(TestProfiles.indianApplicant(), profile(SKELETON));

        store.remember("how did you hear", "A friend");

        // The profile already answers "how did you hear" with something else. A
        // refinement placed below the entry it refines never fires.
        assertThat(store.all().getFirst().answer()).isEqualTo("A friend");
    }

    @Test
    @DisplayName("a blank answer is refused rather than written")
    void blankIsRefused() throws IOException {
        Path file = profile(SKELETON);
        AnswerStore store = new AnswerStore(TestProfiles.indianApplicant(), file);

        // A blank entry matches the question and then fills the field with
        // nothing, which is worse than the form stopping and saying why.
        assertThatThrownBy(() -> store.remember("some question", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("An answer is needed");
        assertThat(Files.readString(file)).isEqualTo(SKELETON);
    }

    @Test
    @DisplayName("the previous profile is kept before anything is written")
    void backsUpFirst() throws IOException {
        Path file = profile(SKELETON);
        new AnswerStore(TestProfiles.indianApplicant(), file)
                .remember("a question", "an answer");

        assertThat(Files.readString(file.resolveSibling("applicant.yml.bak")))
                .isEqualTo(SKELETON);
    }

    @Test
    @DisplayName("a profile without the anchor is left alone and says so")
    void refusesAnUnfamiliarProfile() throws IOException {
        Path file = profile("job-radar:\n  applicant:\n    name:\n      first: Ada\n");
        AnswerStore store = new AnswerStore(TestProfiles.indianApplicant(), file);

        String said = store.remember("a question", "an answer");

        assertThat(said).contains("until restart only");
        assertThat(Files.readString(file)).doesNotContain("an answer");
        // Still usable this session: the point is not to lose the answer, only
        // to refuse to guess where it goes in a file shaped unexpectedly.
        assertThat(store.answers("a question")).isTrue();
    }

    @Test
    @DisplayName("quotes and backslashes in a question survive the round trip")
    void escapes() throws IOException {
        Path file = profile(SKELETON);
        new AnswerStore(TestProfiles.indianApplicant(), file)
                .remember("do you \"need\" sponsorship", "Yes");

        assertThat(Files.readString(file)).contains("\\\"need\\\"");
    }
}
