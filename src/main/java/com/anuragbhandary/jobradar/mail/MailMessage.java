package com.anuragbhandary.jobradar.mail;

import java.time.Instant;

/**
 * One email, reduced to the parts that decide anything.
 *
 * @param from    the raw From header, display name and address together. Both
 *                halves matter: the address gives the domain, the display name is
 *                usually the only place the company name appears when the mail is
 *                sent through an ATS.
 * @param snippet the first part of the body. Enough for classification - rejection
 *                and interview language is always in the opening lines - and small
 *                enough that a scan of 500 messages is not a download.
 */
public record MailMessage(
        String id, String threadId, String from, String subject, String snippet, Instant received) {

    /** The bit before {@code <address>}, or the address when there is no display name. */
    public String senderName() {
        int bracket = from == null ? -1 : from.indexOf('<');
        if (bracket > 0) {
            return from.substring(0, bracket).replace("\"", "").trim();
        }
        return from == null ? "" : from.trim();
    }

    /** The sending domain, lowercased, or empty. */
    public String senderDomain() {
        if (from == null) {
            return "";
        }
        int at = from.lastIndexOf('@');
        if (at < 0) {
            return "";
        }
        String tail = from.substring(at + 1).replace(">", "").trim();
        return tail.toLowerCase(java.util.Locale.ROOT);
    }
}
