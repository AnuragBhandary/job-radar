package com.anuragbhandary.jobradar.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gmail access.
 *
 * <p>Off unless a client-secret file exists. Unlike Sheets this cannot use the
 * service account already configured: a service account has no mailbox, and
 * reading a personal Gmail with one requires domain-wide delegation that a
 * consumer account cannot grant. So it is the installed-app OAuth flow, and the
 * user consents once in a browser.
 *
 * @param credentialsPath OAuth client secret, downloaded from the Cloud Console
 * @param tokensDir       where the refresh token is cached. A live credential to
 *                        the applicant's mailbox - outside the repo, and in
 *                        .gitignore
 * @param scanDays        how far back to look. Replies to an application older
 *                        than this are not going to arrive
 */
@ConfigurationProperties(prefix = "job-radar.gmail")
public record GmailProperties(String credentialsPath, String tokensDir, int scanDays) {

    public int scanDays() {
        return scanDays <= 0 ? 60 : scanDays;
    }
}
