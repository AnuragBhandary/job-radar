package com.anuragbhandary.jobradar.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one account that can open the review queue.
 *
 * <p>A single username and a bcrypt hash, in
 * {@code ~/.config/job-radar/secrets.yml} beside the API keys. There is no user
 * table because there is no second user, and there never should be: this UI can
 * read a Gmail inbox, drive a signed-in browser, and send a job application under
 * somebody's name. An account system implies accounts, and the right number here
 * is one.
 *
 * @param username  what to type. Defaults to "me" - it is not a secret and a
 *                  guessable username with a strong password is the normal shape
 * @param passwordHash a bcrypt hash, never the password. Generate one with the
 *                  {@code passwd} command, which prints a line to paste in
 * @param sessionHours how long a login lasts. Shorter matters more the moment
 *                  this is reachable from anywhere but the machine it runs on
 */
@ConfigurationProperties(prefix = "job-radar.auth")
public record AuthProperties(String username, String passwordHash, int sessionHours) {

    public String username() {
        return username == null || username.isBlank() ? "me" : username;
    }

    public int sessionHours() {
        return sessionHours <= 0 ? 24 : sessionHours;
    }

    /** False when no password has been set, which is a startup decision. */
    public boolean isConfigured() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
