package com.anuragbhandary.jobradar.evidence;

import static com.anuragbhandary.jobradar.evidence.EvidenceFixtures.BANK_YAML;
import static com.anuragbhandary.jobradar.evidence.EvidenceFixtures.errors;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading the file: what is read, what is refused, and that the ids do not move. */
class EvidenceBankLoaderTest {

    @Test
    @DisplayName("the fixture loads whole: four sources, nine items, no problems")
    void loadsTheFixture() {
        EvidenceBank bank = EvidenceFixtures.bank();

        assertThat(bank.problems()).isEmpty();
        assertThat(bank.sources()).extracting(EvidenceSource::id)
                .containsExactly("acme", "scheduler", "docs", "site");
        assertThat(bank.items()).hasSize(9);
        EvidenceItem replay = bank.find("acme-replay").orElseThrow();
        assertThat(replay.claim()).isEqualTo(EvidenceFixtures.REPLAY);
        assertThat(replay.metrics()).containsExactly("12 streams", "approximately 3,000 messages");
        assertThat(replay.strength()).isEqualTo(EvidenceItem.Strength.HIGH);
        assertThat(replay.variants()).hasSize(3);
        assertThat(replay.approvedVariants()).extracting(EvidenceItem.Variant::id)
                .containsExactly("events-first", "dedup-first");
    }

    @Test
    @DisplayName("omitted fields take the safe default: individual, medium, and not approved")
    void defaults() {
        EvidenceBank bank = EvidenceFixtures.bank();

        EvidenceItem persist = bank.find("sched-persist").orElseThrow();
        assertThat(persist.attribution()).isEqualTo(EvidenceItem.Attribution.INDIVIDUAL);
        assertThat(persist.strength()).isEqualTo(EvidenceItem.Strength.MEDIUM);
        EvidenceItem.Variant proposed = bank.find("acme-replay").orElseThrow().variants().stream()
                .filter(v -> v.id().equals("proposed")).findFirst().orElseThrow();
        assertThat(proposed.approved()).isFalse();
        assertThat(bank.find("acme-replay").orElseThrow().approvedTexts())
                .doesNotContain(EvidenceFixtures.UNAPPROVED);
    }

    @Test
    @DisplayName("a file on disk reads the same as the same text")
    void loadsFromDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("evidence.yml");
        Files.writeString(file, BANK_YAML);

        EvidenceBank bank = EvidenceBankLoader.load(file);

        assertThat(bank.origin()).isEqualTo(file.toString());
        assertThat(bank.items()).extracting(EvidenceItem::id)
                .isEqualTo(EvidenceFixtures.bank().items().stream().map(EvidenceItem::id).toList());
    }

    @Test
    @DisplayName("no file is an empty bank and a warning, not an error")
    void missingFile(@TempDir Path dir) {
        EvidenceBank bank = EvidenceBankLoader.load(dir.resolve("nope.yml"));

        assertThat(bank.isEmpty()).isTrue();
        assertThat(bank.hasErrors()).isFalse();
        assertThat(bank.problems()).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("as before"));
    }

    @Test
    @DisplayName("an empty file, broken YAML, a list at the top, or a future version refuse the file")
    void unreadableFiles() {
        assertThat(errors(EvidenceFixtures.bank(""))).singleElement().asString().contains("empty");
        assertThat(errors(EvidenceFixtures.bank("items: [\n  - id: x"))).singleElement().asString()
                .contains("not valid YAML");
        assertThat(errors(EvidenceFixtures.bank("- id: x\n"))).singleElement().asString()
                .contains("expected a mapping");
        assertThat(errors(EvidenceFixtures.bank("version: 2\nitems: []\n"))).singleElement().asString()
                .contains("version 2");
    }

    @Test
    @DisplayName("a misspelt field refuses the whole file and names the field and where it is")
    void unknownFieldRefusesTheFile() {
        String typo = BANK_YAML.replaceFirst("qualifiers:", "qualifers:");

        EvidenceBank bank = EvidenceFixtures.bank(typo);

        assertThat(bank.isEmpty()).isTrue();
        assertThat(errors(bank)).singleElement().asString()
                .contains("'qualifers'")
                .contains("items[2]")
                .contains("silently drop");
    }

    @Test
    @DisplayName("a value outside an enum refuses the file with its path")
    void badEnum() {
        EvidenceBank bank = EvidenceFixtures.bank(BANK_YAML.replaceFirst("strength: high", "strength: enormous"));

        assertThat(bank.isEmpty()).isTrue();
        assertThat(errors(bank)).singleElement().asString().contains("items[0].strength");
    }

    @Test
    @DisplayName("ids are written, not counted: reordering the file changes no id and no claim")
    void stableIds() {
        EvidenceBank once = EvidenceFixtures.bank();
        EvidenceBank again = EvidenceFixtures.bank();

        assertThat(again.items()).extracting(EvidenceItem::id)
                .isEqualTo(once.items().stream().map(EvidenceItem::id).toList());

        // The same items, listed in reverse.
        String items = EvidenceFixtures.ITEMS_YAML.substring("items:\n".length());
        List<String> blocks = new ArrayList<>(List.of(items.split("(?m)^(?=  - id: )")));
        blocks.removeIf(String::isBlank);
        Collections.reverse(blocks);
        EvidenceBank reversed = EvidenceFixtures.bank(EvidenceFixtures.SOURCES_YAML + "items:\n"
                + String.join("", blocks));

        assertThat(reversed.problems()).isEmpty();
        Map<String, String> before = once.items().stream()
                .collect(Collectors.toMap(EvidenceItem::id, EvidenceItem::claim));
        Map<String, String> after = reversed.items().stream()
                .collect(Collectors.toMap(EvidenceItem::id, EvidenceItem::claim));
        assertThat(after).isEqualTo(before);
        assertThat(reversed.items().getFirst().id()).isEqualTo("site-static");
        assertThat(reversed.items().stream().collect(Collectors.toMap(EvidenceItem::id,
                Function.identity())).get("acme-replay").approvedTexts())
                .isEqualTo(once.find("acme-replay").orElseThrow().approvedTexts());
    }
}
