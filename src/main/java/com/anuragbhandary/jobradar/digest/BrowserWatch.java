package com.anuragbhandary.jobradar.digest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Careers pages job-radar cannot fetch, to be read in a browser during review.
 *
 * <p>Darwinbox serves its job lists from an API behind Cloudflare's bot check:
 * a browser gets the jobs, a program gets "403 Attention Required" (verified
 * 2026-09-28 on two tenants). Getting past that would mean evading the check,
 * so these pages are not fetched. They are listed at the end of every openings
 * file instead, and read the ordinary way during the review.
 *
 * <p>By 2026-10-23 the list held 47 pages, too many to open every day, so each
 * has a cadence: daily pages are listed in every file, weekly ones in full on
 * Mondays (a count otherwise), monthly ones in full on the 1st. A page without
 * one is daily, as every page was before.
 */
@ConfigurationProperties(prefix = "job-radar.browser-watch")
public record BrowserWatch(List<Page> pages) {

    /** How often a page is worth opening. */
    public enum Cadence { DAILY, WEEKLY, MONTHLY }

    /**
     * @param note    what the page is for, or why it is on the list
     * @param cadence daily, weekly or monthly; null is daily
     */
    public record Page(String company, String city, String url, String note, String cadence) {

        Cadence cadenceOrDaily() {
            if (cadence == null || cadence.isBlank()) {
                return Cadence.DAILY;
            }
            return Cadence.valueOf(cadence.trim().toUpperCase(Locale.ROOT));
        }
    }

    public List<Page> pages() {
        return pages == null ? List.of() : pages;
    }

    /** The section appended to an openings file, or "" when nothing is watched. */
    public String render() {
        return render(LocalDate.now());
    }

    /** As {@link #render()}, for a given day: Mondays add the weekly pages, the 1st the monthly. */
    public String render(LocalDate today) {
        if (pages().isEmpty()) {
            return "";
        }
        boolean weekly = today.getDayOfWeek() == DayOfWeek.MONDAY;
        boolean monthly = today.getDayOfMonth() == 1;
        List<Page> daily = of(Cadence.DAILY);
        List<Page> weeklyPages = of(Cadence.WEEKLY);
        List<Page> monthlyPages = of(Cadence.MONTHLY);
        int shown = daily.size() + (weekly ? weeklyPages.size() : 0) + (monthly ? monthlyPages.size() : 0);

        StringBuilder out = new StringBuilder("\n## Check in the browser (")
                .append(shown).append(" of ").append(pages().size()).append(")\n")
                .append("Not fetchable (bot checks, robots.txt, LinkedIn-only or no public feed). Open each, "
                        + "read the first page of jobs, and judge any software role like the ones above.\n");
        section(out, "Daily", daily);
        if (weekly) {
            section(out, "Weekly (Mondays)", weeklyPages);
        }
        if (monthly) {
            section(out, "Monthly (the 1st)", monthlyPages);
        }
        boolean weeklyWaiting = !weekly && !weeklyPages.isEmpty();
        boolean monthlyWaiting = !monthly && !monthlyPages.isEmpty();
        if (weeklyWaiting || monthlyWaiting) {
            out.append("\nNot due today: ");
            if (weeklyWaiting) {
                out.append(weeklyPages.size()).append(" weekly (Mondays)");
            }
            if (weeklyWaiting && monthlyWaiting) {
                out.append(", ");
            }
            if (monthlyWaiting) {
                out.append(monthlyPages.size()).append(" monthly (the 1st)");
            }
            out.append(".\n");
        }
        return out.toString();
    }

    private List<Page> of(Cadence cadence) {
        return pages().stream().filter(p -> p.cadenceOrDaily() == cadence).toList();
    }

    private static void section(StringBuilder out, String heading, List<Page> pages) {
        if (pages.isEmpty()) {
            return;
        }
        out.append("\n### ").append(heading).append(" (").append(pages.size()).append(")\n");
        for (Page p : pages) {
            out.append("- ").append(p.company());
            if (p.city() != null && !p.city().isBlank()) {
                out.append(" (").append(p.city()).append(')');
            }
            out.append(": ").append(p.url());
            if (p.note() != null && !p.note().isBlank()) {
                out.append(" · ").append(p.note());
            }
            out.append('\n');
        }
    }
}
