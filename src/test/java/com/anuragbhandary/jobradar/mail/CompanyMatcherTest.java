package com.anuragbhandary.jobradar.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompanyMatcherTest {

    private static final List<String> TRACKER =
            List.of("Celonis", "N26", "Fayble Inc.", "HelloFresh", "Supabase");

    private static MailMessage from(String sender, String subject) {
        return new MailMessage("1", "t1", sender, subject, "body", Instant.EPOCH);
    }

    @Test
    @DisplayName("the display name identifies the company when the domain is an ATS")
    void usesTheDisplayNameForAtsMail() {
        // no-reply@greenhouse.io is the applicant tracking system, not the
        // employer. Matching on the domain finds "Greenhouse", which is on
        // nobody's tracker - so it matches nothing and the feature looks like it
        // works while doing nothing.
        assertThat(CompanyMatcher.match(
                from("Celonis Recruiting <no-reply@greenhouse.io>", "Your application"),
                TRACKER))
                .contains("Celonis");
    }

    @Test
    void usesTheSubjectWhenThereIsNoDisplayName() {
        assertThat(CompanyMatcher.match(
                from("<notifications@ashbyhq.com>", "Your application to N26"), TRACKER))
                .contains("N26");
    }

    @Test
    @DisplayName("a real company domain matches on its own")
    void usesTheDomainWhenItIsNotAnAts() {
        assertThat(CompanyMatcher.match(
                from("careers@supabase.com", "Following up"), TRACKER))
                .contains("Supabase");
        assertThat(CompanyMatcher.match(
                from("talent@jobs.hellofresh.de", "Update"), TRACKER))
                .contains("HelloFresh");
    }

    @Test
    @DisplayName("'Inc.' does not make every American company a match")
    void noiseWordsAreIgnored() {
        assertThat(CompanyMatcher.match(
                from("Fayble <hi@fayble.com>", "Hello"), TRACKER))
                .contains("Fayble Inc.");
        // "Inc" alone identifies nothing.
        assertThat(CompanyMatcher.match(
                from("Some Startup Inc. <hi@example.com>", "Hello"), TRACKER))
                .isEmpty();
    }

    @Test
    @DisplayName("no match is empty, never a guess")
    void refusesRatherThanGuessing() {
        // A wrong match writes "Rejected" onto the wrong row of the only record
        // that cannot be rebuilt.
        assertThat(CompanyMatcher.match(
                from("Amazon <ship@amazon.in>", "Your order"), TRACKER)).isEmpty();
        assertThat(CompanyMatcher.match(from("", ""), TRACKER)).isEmpty();
        assertThat(CompanyMatcher.match(from("x@y.com", "hi"), List.of())).isEmpty();
    }

    @Test
    @DisplayName("the longer, more specific company name wins")
    void prefersTheMoreSpecificName() {
        assertThat(CompanyMatcher.match(
                from("N26 Bank Recruiting <r@greenhouse.io>", "Update"),
                List.of("N26", "N26 Bank")))
                .contains("N26 Bank");
    }

    @Test
    void tokenisationDropsSuffixesAndSingleCharacters() {
        assertThat(CompanyMatcher.significantTokens("Fayble Inc.")).containsExactly("fayble");
        assertThat(CompanyMatcher.significantTokens("Acme Technologies Private Limited"))
                .containsExactly("acme");
        assertThat(CompanyMatcher.significantTokens("A B C")).isEmpty();
    }
}
