package com.anuragbhandary.jobradar.mail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides which application an email is about.
 *
 * <p>Harder than it looks, and the reason it is a class of its own with tests. The
 * sender is rarely the company: ATS mail arrives from {@code no-reply@greenhouse.io},
 * {@code notifications@ashbyhq.com} or {@code @myworkday.com}, so the domain is the
 * applicant tracking system rather than the employer. What identifies the company
 * is the display name ("Celonis Recruiting") or the subject ("Your application to
 * N26"), and either may be missing.
 *
 * <p>It returns empty rather than a best guess. A wrong match writes "Rejected"
 * onto the wrong row of the only record that cannot be rebuilt, and the cost of
 * that is not a wasted email - it is that a live application looks dead and stops
 * being followed up.
 */
public final class CompanyMatcher {

    private CompanyMatcher() {
    }

    /**
     * Domains belonging to applicant tracking systems rather than to employers.
     *
     * <p>Without this list, every Greenhouse-sent rejection matches the "company"
     * Greenhouse - which is on nobody's tracker, so it silently matches nothing,
     * and the feature appears to work while doing nothing at all.
     */
    private static final List<String> ATS_DOMAINS = List.of(
            "greenhouse.io", "ashbyhq.com", "lever.co", "hire.lever.co",
            "smartrecruiters.com", "myworkday.com", "workday.com", "workable.com",
            "recruitee.com", "personio.de", "teamtailor.com", "icims.com",
            "successfactors.com", "taleo.net", "jobvite.com", "bamboohr.com");

    /** Words that carry no identity and would match half the tracker. */
    private static final List<String> NOISE = List.of(
            "inc", "inc.", "ltd", "ltd.", "limited", "llc", "gmbh", "bv", "b.v.",
            "corp", "corporation", "company", "co", "technologies", "technology",
            "labs", "software", "systems", "solutions", "group", "the", "and",
            "recruiting", "recruitment", "talent", "careers", "hiring", "team",
            "india", "private");

    /**
     * The company whose name best identifies this message.
     *
     * @param companies the company names on the tracker
     * @return the matching name, or empty when nothing matches unambiguously
     */
    public static Optional<String> match(MailMessage message, List<String> companies) {
        String haystack = (message.senderName() + " " + nullSafe(message.subject()))
                .toLowerCase(Locale.ROOT);

        // The domain is only evidence when it is not an ATS.
        String domain = message.senderDomain();
        boolean domainIsUseful = !domain.isBlank()
                && ATS_DOMAINS.stream().noneMatch(domain::endsWith);
        String domainRoot = domainIsUseful ? rootOf(domain) : "";

        String best = null;
        int bestLength = 0;
        boolean ambiguous = false;

        for (String company : companies) {
            List<String> tokens = significantTokens(company);
            if (tokens.isEmpty()) {
                continue;
            }
            boolean hit = tokens.stream().allMatch(token -> containsWord(haystack, token))
                    || (!domainRoot.isEmpty() && tokens.contains(domainRoot));
            if (!hit) {
                continue;
            }
            // Longest name wins: "N26" and "N26 Bank" both match, and the more
            // specific one is the better answer. Equal lengths are a genuine
            // ambiguity and are refused.
            int length = company.length();
            if (best == null || length > bestLength) {
                best = company;
                bestLength = length;
                ambiguous = false;
            } else if (length == bestLength && !company.equalsIgnoreCase(best)) {
                ambiguous = true;
            }
        }
        return ambiguous ? Optional.empty() : Optional.ofNullable(best);
    }

    /**
     * The parts of a company name that actually identify it.
     *
     * <p>"Fayble Inc." reduces to ["fayble"]. Keeping "inc" would match every
     * American company in the tracker, and requiring it would miss the mail that
     * says just "Fayble".
     */
    static List<String> significantTokens(String company) {
        if (company == null) {
            return List.of();
        }
        return java.util.Arrays.stream(
                        company.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .filter(token -> !NOISE.contains(token))
                // A one-character token is not evidence of anything.
                .filter(token -> token.length() > 1)
                .toList();
    }

    /** "jobs.n26.com" -> "n26". The label before the public suffix, roughly. */
    private static String rootOf(String domain) {
        String[] parts = domain.split("\\.");
        if (parts.length < 2) {
            return domain;
        }
        // Handles co.uk and com.au by stepping back one more when the
        // second-to-last label is itself a suffix-ish word.
        String candidate = parts[parts.length - 2];
        if (parts.length >= 3 && List.of("co", "com", "org", "net").contains(candidate)) {
            candidate = parts[parts.length - 3];
        }
        return candidate;
    }

    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
