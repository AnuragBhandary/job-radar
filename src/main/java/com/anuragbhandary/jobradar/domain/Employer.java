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

    public static String company(String boardLabel, Source source, String title) {
        return split(boardLabel, source, title)[0];
    }
}
