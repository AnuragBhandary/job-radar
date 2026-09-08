package com.anuragbhandary.jobradar.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consent, driven from a button instead of a terminal.
 *
 * <p>The flow underneath opens a browser and blocks until Google redirects back,
 * which can be a minute if an account has to be chosen and a warning clicked
 * through. A web request cannot wait that long, so the button starts the flow on
 * its own thread and the page reports on it.
 *
 * <p>Why this exists at all: an OAuth consent screen still in Testing hands out
 * refresh tokens that expire after seven days. Re-consenting is not a one-off
 * setup step here, it is a recurring chore, and a chore that needs a terminal is
 * a chore that gets skipped.
 */
@Service
public class GmailConnection {

    private static final Logger log = LoggerFactory.getLogger(GmailConnection.class);

    /** How long to leave a started flow looking live before calling it abandoned. */
    private static final Duration PATIENCE = Duration.ofMinutes(5);

    public enum State {
        /** No client secret on disk. Nothing to offer but instructions. */
        UNCONFIGURED,
        /** Configured, no token. */
        DISCONNECTED,
        /** A browser is open and Google has not redirected back yet. */
        WAITING,
        CONNECTED,
        FAILED
    }

    public record Status(State state, String detail, Optional<Instant> since) {

        public boolean busy() {
            return state == State.WAITING;
        }
    }

    private final GmailClient gmail;

    private volatile Thread worker;
    private volatile Instant startedAt;
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
        if (waiting()) {
            return new Status(State.WAITING,
                    "A browser window is open. Approve it there, and pick the mailbox "
                            + "you want read rather than whichever account Google offers first.",
                    Optional.ofNullable(startedAt));
        }
        if (gmail.hasToken()) {
            return new Status(State.CONNECTED, null, gmail.tokenWrittenAt());
        }
        if (failure != null) {
            return new Status(State.FAILED, failure, Optional.empty());
        }
        return new Status(State.DISCONNECTED, null, Optional.empty());
    }

    /**
     * Starts the consent flow, unless one is already running.
     *
     * @return what to tell the user right now
     */
    public synchronized String connect() {
        if (!gmail.isConfigured()) {
            return "There is no OAuth client to connect with yet.";
        }
        if (waiting()) {
            return "A browser window is already open for this. Finish that one.";
        }
        failure = null;
        startedAt = Instant.now();
        worker = Thread.ofVirtual().name("gmail-consent").start(() -> {
            try {
                gmail.authorise();
                log.info("Gmail connected");
            } catch (Exception e) {
                // Includes the user simply closing the tab, which is not an error
                // worth a stack trace, only a sentence on the page.
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Gmail consent did not complete: {}", failure);
            }
        });
        return "Opening a browser. Approve it there, then come back to this page.";
    }

    /** Throws the token away. The next scan will ask for consent again. */
    public synchronized String disconnect() {
        try {
            gmail.forget();
            failure = null;
            return "Disconnected. The stored token is gone.";
        } catch (Exception e) {
            return "Could not remove the token: " + e.getMessage();
        }
    }

    private boolean waiting() {
        Thread current = worker;
        if (current == null || !current.isAlive()) {
            return false;
        }
        // A flow nobody is going to finish should not pin the page on "waiting"
        // forever, so it stops being reported as live after a while. The thread
        // itself is left alone; it is parked on a socket and harmless.
        return startedAt != null && Duration.between(startedAt, Instant.now()).compareTo(PATIENCE) < 0;
    }
}
