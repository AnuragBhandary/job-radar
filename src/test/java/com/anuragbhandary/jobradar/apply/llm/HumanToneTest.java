package com.anuragbhandary.jobradar.apply.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HumanToneTest {

    // -----------------------------------------------------------------------
    // Dashes. The distinction between punctuation and a hyphen inside a word is
    // the whole reason this is not a search and replace.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dash punctuation becomes a comma")
    void removesDashPunctuation() {
        assertThat(HumanTone.removeDashes("I built the pipeline — it handled 1,274 messages."))
                .isEqualTo("I built the pipeline, it handled 1,274 messages.");
        assertThat(HumanTone.removeDashes("Kafka – the ordering was the hard part."))
                .isEqualTo("Kafka, the ordering was the hard part.");
        assertThat(HumanTone.removeDashes("Two services - one queue."))
                .isEqualTo("Two services, one queue.");
        assertThat(HumanTone.removeDashes("Three things -- all of them backend."))
                .isEqualTo("Three things, all of them backend.");
    }

    @Test
    @DisplayName("a hyphen inside a word is left alone")
    void keepsRealHyphens() {
        // Stripping these turns the resume's own vocabulary into "event driven"
        // and "scikitlearn".
        String text = "event-driven services, full-time, scikit-learn and e-commerce";
        assertThat(HumanTone.removeDashes(text)).isEqualTo(text);
        assertThat(HumanTone.removeDashes("A Kafka-based replay system"))
                .isEqualTo("A Kafka-based replay system");
    }

    @Test
    @DisplayName("a dash after a comma just goes")
    void doesNotDoubleUpPunctuation() {
        assertThat(HumanTone.removeDashes("I built it, — and it worked."))
                .isEqualTo("I built it, and it worked.");
    }

    @Test
    void detectsWhatItCouldNotFix() {
        assertThat(HumanTone.hasDashes("Plain text with no dashes.")).isFalse();
        assertThat(HumanTone.hasDashes("event-driven systems")).isFalse();
        assertThat(HumanTone.hasDashes("Something — else")).isTrue();
        assertThat(HumanTone.hasDashes("Something - else")).isTrue();
    }

    // -----------------------------------------------------------------------
    // Vocabulary. Rejected rather than edited: the tells are whole phrases and
    // cutting one out leaves the sentence around it still shaped wrong.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the usual machine phrasings are caught")
    void findsTells() {
        assertThat(HumanTone.tells("I am passionate about building robust and scalable systems."))
                .contains("passionate about", "robust and scalable");
        assertThat(HumanTone.tells("Moreover, I would leverage my expertise to spearhead this."))
                .contains("moreover", "leverage my", "spearhead");
        assertThat(HumanTone.tells("I am excited about the opportunity to delve into this realm of work."))
                .contains("i am excited about the opportunity", "delve");
        assertThat(HumanTone.tells("This role aligns perfectly with my proven track record."))
                .contains("aligns perfectly", "proven track record");
    }

    @Test
    @DisplayName("the rhetorical shapes survive a vocabulary filter, so they are checked too")
    void findsShapes() {
        assertThat(HumanTone.tells("Not only did I build it, but also I ran it."))
                .contains("not only", "but also");
    }

    @Test
    @DisplayName("plain writing about the same work passes")
    void acceptsHumanProse() {
        String letter = """
                I built the audio side of a real-time sports commentary system. The
                interesting part was latency: generating one emotion per play instead
                of per sentence cut generation time by about a third.

                Before that I put together a Kafka replay system that handled 19 games
                without duplicating or reordering anything. Your posting mentions
                event-driven work, so that is probably the closest thing I have done.
                """;

        assertThat(HumanTone.tells(letter)).isEmpty();
        assertThat(HumanTone.hasDashes(letter)).isFalse();
    }

    @Test
    @DisplayName("paragraph breaks survive the dash rewrite")
    void doesNotEatParagraphs() {
        // `\\s` includes newlines, so collapsing runs of whitespace destroyed every
        // paragraph break - after which the structure check rejected the model for
        // writing one block it had not written. Two rounds of prompt engineering
        // went into a fault in one regex.
        String twoParagraphs = "First paragraph — with a dash.\n\nSecond paragraph.";

        String cleaned = HumanTone.removeDashes(twoParagraphs);

        assertThat(cleaned).contains("\n\n");
        assertThat(HumanTone.structureProblems(cleaned)).isEmpty();
    }

    @Test
    @DisplayName("one unbroken block is a structure problem, a short answer is not")
    void judgesStructureOnlyWhereItMatters() {
        String block = "I built a thing. ".repeat(30);
        assertThat(HumanTone.structureProblems(block))
                .anyMatch(p -> p.contains("unbroken block"));

        // Most sentences opening with "I" is a list of achievements.
        assertThat(HumanTone.structureProblems(
                "I built A.\n\nI built B. I built C. I built D."))
                .anyMatch(p -> p.contains("begin with"));

        // A short answer to a form question is fine as one line starting with I.
        assertThat(HumanTone.structureProblems("I have one year of backend work."))
                .isEmpty();
    }

    @Test
    @DisplayName("wording lifted from the worked example is contraband")
    void catchesBorrowedPhrasing() {
        // The example exists so the model produces paragraph breaks. Having added
        // it, the model copied its closing two sentences verbatim - twenty letters
        // with the same ending is the problem the example was added to solve.
        assertThat(HumanTone.borrowedFromExample(
                "That work is the closest thing I have to this role. Happy to talk whenever suits."))
                .isNotEmpty();
        assertThat(HumanTone.borrowedFromExample(
                "I built a Kafka replay system that preserved ordering.")).isEmpty();
    }

    @Test
    void handlesNothing() {
        assertThat(HumanTone.removeDashes(null)).isNull();
        assertThat(HumanTone.tells(null)).isEmpty();
        assertThat(HumanTone.tells("")).isEmpty();
        assertThat(HumanTone.hasDashes(null)).isFalse();
    }

    @Test
    @DisplayName("the retry names what was wrong, so the second attempt is a different request")
    void retryNoteIsSpecific() {
        String note = HumanTone.retryNote(java.util.List.of("passionate about", "delve"), true);

        assertThat(note).contains("passionate about").contains("delve").contains("dash");
    }

    @Test
    void styleRulesForbidTheThingsTheFilterChecks() {
        String rules = HumanTone.styleRules();

        assertThat(rules).contains("No dashes").contains("event-driven")
                .contains("passionate").contains("leverage");
    }
}
