package com.anuragbhandary.jobradar.mail;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consent, as an ordinary web round-trip.
 *
 * <p>It used to run Google's installed-app helper, which starts a second web
 * server on its own port, opens a browser, and blocks until the redirect lands.
 * Every part of that was wrong here. Spring Boot runs the JVM headless so no
 * browser ever opened; the helper quietly printed the address to a log instead.
 * The blocking call had to be pushed onto a background thread, and when a user
 * gave up, the listener stayed on the port, so the next attempt collided with
 * the last one and reported "Address already in use" forever.
 *
 * <p>None of that is needed. This application is already a web server with a
 * browser pointed at it. So consent is a link the user clicks and a callback
 * that lands back here: no second port, no thread, no window to fail to open,
 * and nothing that can be left running.
 */
@Service
public class GmailConnection {

    private static final Logger log = LoggerFactory.getLogger(GmailConnection.class);

    /** Past this age a token is close enough to Google's Testing-mode expiry to warn. */
    private static final Duration NEARLY_STALE = Duration.ofDays(6);

    public enum State {
        /** No client secret on disk. Nothing to offer but instructions. */
        UNCONFIGURED,
        /** Configured, no token. There is a link to click. */
        DISCONNECTED,
        CONNECTED,
        /** An approval came back and could not be exchanged. */
        FAILED
    }

    public record Status(State state, String detail, Optional<Instant> since) {

        public boolean connected() {
            return state == State.CONNECTED;
        }

        /**
         * True when the approval is old enough that Google may be about to expire
         * it. An app whose consent screen is still in Testing gets refresh tokens
         * lasting seven days, so the page warns on the sixth rather than letting
         * the next scan be the thing that tells him.
         */
        public boolean nearlyStale() {
            return since.filter(when -> Duration.between(when, Instant.now())
                    .compareTo(NEARLY_STALE) > 0).isPresent();
        }
    }

    private final GmailClient gmail;
    private volatile String failure;

    public GmailConnection(GmailClient gmail) {
        this.gmail = gmail;
    }

    public Status status() {
        if (!gmail.isConfigured()) {
            return new Status(State.UNCONFIGURED,
                    "No OAuth client on disk. Create one (Desktop app) in the Cloud "
                            + "Console and save the JSON to ~/.config/job-radar/gmail-oauth.json.",
                    Optional.empty());
        }
        if (gmail.hasToken()) {
            return new Status(State.CONNECTED, null, gmail.tokenWrittenAt());
        }
        if (failure != null) {
            return new Status(State.FAILED, failure, Optional.empty());
        }
        return new Status(State.DISCONNECTED, null, Optional.empty());
    }

    /** The Google page to link to, or empty when there is no client to link with. */
    public Optional<String> approvalLink(String redirectUri) {
        if (!gmail.isConfigured()) {
            return Optional.empty();
        }
        try {
            failure = null;
            return Optional.of(gmail.authorisationUrl(redirectUri));
        } catch (IOException | RuntimeException e) {
            failure = describe(e);
            log.warn("Could not build the Google approval link: {}", failure);
            return Optional.empty();
        }
    }

    /** Called when Google redirects back. Returns what to tell the user. */
    public String complete(String code, String error, String redirectUri) {
        if (error != null && !error.isBlank()) {
            // "access_denied" is the normal outcome of clicking Cancel, and
            // saying so beats reporting a raw error code as a failure.
            failure = "access_denied".equals(error)
                    ? "Approval was cancelled, so nothing changed."
                    : "Google refused the approval: " + error;
            return failure;
        }
        if (code == null || code.isBlank()) {
            failure = "Google came back without an approval code.";
            return failure;
        }
        try {
            gmail.completeAuthorisation(code, redirectUri);
            failure = null;
            return "Connected. Replies will be read from now on.";
        } catch (IOException | RuntimeException e) {
            failure = describe(e);
            log.warn("Could not exchange the approval: {}", failure);
            return "Could not finish connecting: " + failure;
        }
    }

    /** Throws the token away. The next scan needs a fresh approval. */
    public String disconnect() {
        try {
            gmail.forget();
            failure = null;
            return "Disconnected. The stored token is gone.";
        } catch (IOException | RuntimeException e) {
            return "Could not remove the token: " + describe(e);
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
