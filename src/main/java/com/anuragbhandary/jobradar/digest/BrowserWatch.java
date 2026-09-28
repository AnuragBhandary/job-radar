package com.anuragbhandary.jobradar.digest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Careers pages job-radar cannot fetch, to be read in a browser during review.
 *
 * <p>Darwinbox serves its job lists from an API behind Cloudflare's bot check:
 * a browser gets the jobs, a program gets "403 Attention Required" (verified
 * 2026-09-28 on two tenants). Getting past that would mean evading the check,
 * so these pages are not fetched. They are listed at the end of every openings
 * file instead, and read the ordinary way during the review.
 */
@ConfigurationProperties(prefix = "job-radar.browser-watch")
public record BrowserWatch(List<Page> pages) {

    /** @param note what the page is for, or why it is on the list */
    public record Page(String company, String city, String url, String note) {
    }

    public List<Page> pages() {
        return pages == null ? List.of() : pages;
    }

    /** The section appended to an openings file, or "" when nothing is watched. */
    public String render() {
        if (pages().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n## Check in the browser (")
                .append(pages().size()).append(")\n")
                .append("Not fetchable (Darwinbox is behind a bot check). Open each, read the "
                        + "first page of jobs, and judge any software role like the ones above.\n");
        for (Page p : pages()) {
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
        return out.toString();
    }
}
