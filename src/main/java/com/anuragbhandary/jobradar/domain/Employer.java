package com.anuragbhandary.jobradar.domain;

import java.util.Locale;

/**
 * The real employer and role behind a posting.
 *
 * <p>Aggregators (Arbeitnow, Jobicy, We Work Remotely, the Hacker News thread)
 * are one "board" carrying many employers, so the board label names the
 * aggregator, not the company. The employer is in the title instead: "Role @
 * Company", "Company: Role", or a Hacker News header "Company | ... | Role".
 * Reading the board label there wrote "Arbeitnow" into the tracker and flagged
 * every Arbeitnow posting as a company already applied to.
 */
public final class Employer {

    private Employer() {
    }

    /** {@code [company, role]}. Direct boards return the label and the title unchanged. */
    public static String[] split(String boardLabel, Source source, String title) {
        String t = title == null ? "" : title.strip();
        String label = boardLabel == null ? "" : boardLabel;
        if (source == null) {
            return new String[] {label, t};
        }
        switch (source) {
            case ARBEITNOW, JOBICY -> {
                int at = t.lastIndexOf(" @ ");
                if (at > 0) {
                    return new String[] {t.substring(at + 3).strip(), t.substring(0, at).strip()};
                }
            }
            case WE_WORK_REMOTELY -> {
                int colon = t.indexOf(": ");
                if (colon > 0) {
                    return new String[] {t.substring(0, colon).strip(), t.substring(colon + 2).strip()};
                }
            }
            case HACKER_NEWS -> {
                if (t.contains("|")) {
                    String[] parts = t.split("\\s*\\|\\s*");
                    String role = parts.length > 1 ? parts[1] : t;
                    for (int i = 1; i < parts.length; i++) {
                        String part = parts[i].toLowerCase(Locale.ROOT);
                        if (!part.startsWith("http") && !part.contains(".")
                                && !part.contains("remote") && !part.contains("full time")
                                && !part.contains("full-time")) {
                            role = parts[i];
                            break;
                        }
                    }
                    return new String[] {parts[0].strip(), role.strip()};
                }
            }
            default -> {
            }
        }
        return new String[] {label, t};
    }

    /**
     * Boards that post for employers they do not name. Jobgether lists roles "on
     * behalf of a partner company, who manages all applications" (2026-10-01:
     * 245 candidates, no employer in title or text), so there is no employer's
     * own page to apply on unless the description gives the company away.
     */
    private static final java.util.Set<String> HIDDEN_EMPLOYER_BOARDS = java.util.Set.of(
            "LEVER/jobgether");

    public static boolean hidesEmployer(Source source, String boardToken) {
        return source != null && boardToken != null
                && HIDDEN_EMPLOYER_BOARDS.contains(source + "/" + boardToken.toLowerCase(Locale.ROOT));
    }

    public static String company(String boardLabel, Source source, String title) {
        return split(boardLabel, source, title)[0];
    }

    /**
     * The same role at the same employer, whichever board carried it and however
     * often it was relisted: normalised employer plus normalised role title.
     *
     * <p>Arbeitnow relists roles under new ids and even a new domain (.com and
     * .co.uk), and Canonical's roles arrive through Jobicy as well as its own
     * board. Keyed on posting id or board token, each copy looked new.
     */
    public static String roleKey(String boardLabel, Source source, String title) {
        String[] parts = split(boardLabel, source, title);
        return normaliseCompany(parts[0]) + "|" + normaliseTitle(parts[1]);
    }

    static String normaliseCompany(String company) {
        String c = company == null ? "" : company.toLowerCase(Locale.ROOT);
        c = c.replaceAll("\\(.*?\\)", " ");
        c = c.replaceAll("\\b(gmbh|ag|se|inc|ltd|llc|limited|b\\.?v|pvt|private|co|corp|"
                + "corporation|company|group|technologies|technology|deutschland)\\b\\.?", " ");
        return c.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String normaliseTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        // Gender tags: (f/m/d), (m/w/d), (all genders), (gn), (d/f/m)...
        t = t.replaceAll("\\((?:[mwfdx]\\s*/\\s*){1,3}[mwfdx]\\)|\\(all genders\\)|\\(gn\\)|\\(m/f/x\\)", " ");
        return t.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
