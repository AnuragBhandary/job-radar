package com.anuragbhandary.jobradar.mail;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the inbox. Read-only, and nothing else.
 *
 * <p>The scope is {@code gmail.readonly}, which is not a preference - it is the
 * strongest available statement that a bug here cannot send, delete or label
 * anything. The token cached on disk is limited to what the scope allows, so even
 * a stolen one cannot write.
 *
 * <p>The consent flow opens a browser once and caches a refresh token. On a
 * machine with no browser it fails loudly rather than hanging on a localhost
 * redirect nobody will complete.
 */
@Component
public class GmailClient {

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);
    private static final String APPLICATION_NAME = "job-radar";

    /** The datastore key. One mailbox, so one entry, and the name is arbitrary. */
    private static final String USER = "user";

    private final GmailProperties config;
    private GoogleAuthorizationCodeFlow flow;
    private NetHttpTransport transport;
    private Gmail cached;

    public GmailClient(GmailProperties config) {
        this.config = config;
    }

    public boolean isConfigured() {
        String path = config.credentialsPath();
        return path != null && !path.isBlank() && Files.exists(Path.of(expand(path)));
    }

    /**
     * Recent mail, newest first.
     *
     * <p>The query does the filtering server-side: a mailbox holds tens of
     * thousands of messages and the interesting ones are a few dozen. Only headers
     * and the snippet are fetched - enough to classify, and a fraction of the
     * bytes of a full body.
     */
    public List<MailMessage> recent(int days, int max) throws IOException {
        Gmail gmail = service();
        String query = "newer_than:" + days + "d -in:chats -in:sent";

        List<Message> stubs = new ArrayList<>();
        String pageToken = null;
        do {
            var response = gmail.users().messages().list("me")
                    .setQ(query)
                    .setMaxResults((long) Math.min(500, max - stubs.size()))
                    .setPageToken(pageToken)
                    .execute();
            if (response.getMessages() != null) {
                stubs.addAll(response.getMessages());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && stubs.size() < max);

        List<MailMessage> messages = new ArrayList<>(stubs.size());
        for (Message stub : stubs) {
            try {
                messages.add(toMailMessage(gmail.users().messages().get("me", stub.getId())
                        // METADATA plus the snippet, which Gmail returns anyway.
                        // FULL would download every attachment on the thread.
                        .setFormat("metadata")
                        .setMetadataHeaders(List.of("From", "Subject", "Date"))
                        .execute()));
            } catch (IOException e) {
                // One unreadable message must not end a scan of four hundred.
                log.debug("Skipping message {}: {}", stub.getId(), e.getMessage());
            }
        }
        log.info("Read {} message(s) from the last {} days", messages.size(), days);
        return messages;
    }

    private static MailMessage toMailMessage(Message message) {
        String from = "";
        String subject = "";
        if (message.getPayload() != null && message.getPayload().getHeaders() != null) {
            for (var header : message.getPayload().getHeaders()) {
                if ("From".equalsIgnoreCase(header.getName())) {
                    from = header.getValue();
                } else if ("Subject".equalsIgnoreCase(header.getName())) {
                    subject = header.getValue();
                }
            }
        }
        Instant received = message.getInternalDate() == null
                ? Instant.EPOCH : Instant.ofEpochMilli(message.getInternalDate());
        return new MailMessage(message.getId(), message.getThreadId(),
                from, subject, message.getSnippet(), received);
    }

    /**
     * True when consent has already been given and a refresh token is on disk.
     *
     * <p>Cheap, and does not touch the network - so a page can ask it on every
     * render to decide whether to offer "connect" or "reconnect".
     */
    public boolean hasToken() {
        try {
            return tokenStore().containsKey(USER);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read the stored credential: {}", e.getMessage());
            return false;
        }
    }

    /** When the stored token was last written, for "connected since" on the page. */
    public Optional<Instant> tokenWrittenAt() {
        Path stored = Path.of(expand(config.tokensDir()), "StoredCredential");
        try {
            return Files.exists(stored)
                    ? Optional.of(Files.getLastModifiedTime(stored).toInstant())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The Google page to send the user to.
     *
     * <p>Handed back as a link rather than opened here. Spring Boot runs the JVM
     * headless, so {@code java.awt.Desktop} is unavailable and the library that
     * would "open a browser" silently prints the address to a log nobody reads.
     * A link on the page cannot fail that way.
     *
     * <p>{@code prompt=consent} because Google issues a refresh token only on the
     * first approval; without it a reconnect completes and stores nothing.
     */
    public String authorisationUrl(String redirectUri) throws IOException {
        return flow().newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setApprovalPrompt("force")
                .setAccessType("offline")
                .build();
    }

    /**
     * Exchanges the code Google redirected back with, and stores the token.
     *
     * <p>{@code redirectUri} has to be character-for-character the one used to
     * build the authorisation URL. Google compares them, and a mismatch fails at
     * this step rather than the previous one, which reads as "approval worked but
     * connecting did not".
     */
    public void completeAuthorisation(String code, String redirectUri) throws IOException {
        GoogleTokenResponse response = flow().newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();
        flow().createAndStoreCredential(response, USER);
        cached = null;
        log.info("Gmail connected");
    }

    /**
     * Deletes the stored token and drops the cached client.
     *
     * <p>Needed because an app whose consent screen is still in Testing is issued
     * a refresh token that expires after seven days. When that happens the stored
     * credential is present but dead, and refreshing it fails; the only fix is to
     * throw it away and consent again.
     */
    public void forget() throws IOException {
        cached = null;
        flow = null;
        tokenStore().clear();
    }

    /**
     * The token store on its own, without the OAuth client.
     *
     * <p>Deliberately not routed through the flow. Disconnecting has to work when
     * the client secret has been moved, replaced or corrupted, which is one of the
     * situations you would be disconnecting in.
     *
     * <p>It also has to be {@code containsKey} rather than "does the file exist":
     * the store writes its file the moment it is opened, so the file appears the
     * instant a consent flow starts and long before anyone approves anything.
     */
    private DataStore<StoredCredential> tokenStore() throws IOException {
        return new FileDataStoreFactory(new File(expand(config.tokensDir())))
                .getDataStore(StoredCredential.DEFAULT_DATA_STORE_ID);
    }

    private GoogleAuthorizationCodeFlow flow() throws IOException {
        if (flow != null) {
            return flow;
        }
        try {
            GsonFactory json = GsonFactory.getDefaultInstance();
            GoogleClientSecrets secrets;
            try (FileReader reader = new FileReader(expand(config.credentialsPath()))) {
                secrets = GoogleClientSecrets.load(json, reader);
            }
            flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport(), json, secrets, List.of(GmailScopes.GMAIL_READONLY))
                    .setDataStoreFactory(new FileDataStoreFactory(
                            new File(expand(config.tokensDir()))))
                    .setAccessType("offline")
                    // Without this a second consent returns no refresh token,
                    // because Google only issues one on the first approval.
                    .setApprovalPrompt("force")
                    .build();
            return flow;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Could not set up the Gmail transport", e);
        }
    }

    private NetHttpTransport transport() throws IOException, java.security.GeneralSecurityException {
        if (transport == null) {
            transport = GoogleNetHttpTransport.newTrustedTransport();
        }
        return transport;
    }

    private Gmail service() throws IOException {
        if (cached != null) {
            return cached;
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "No Gmail client secret at " + config.credentialsPath()
                            + ". Create an OAuth client (Desktop app) in the Cloud Console, "
                            + "download the JSON, and put it there.");
        }
        Credential credential = flow().loadCredential(USER);
        if (credential == null) {
            // Deliberately does not start a consent flow. Consent needs a browser
            // and a person, and there is exactly one place that has both: the
            // mail page. A command that tries to do it itself either blocks on a
            // socket nobody will connect to, or opens a second listener that
            // collides with the first.
            throw new IllegalStateException(
                    "Gmail is not connected. Run `ui` and connect it at "
                            + "http://localhost:8080/mail");
        }
        try {
            cached = new Gmail.Builder(transport(), GsonFactory.getDefaultInstance(), credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();
            return cached;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Could not set up the Gmail transport", e);
        }
    }

    private static String expand(String path) {
        return path != null && path.startsWith("~")
                ? System.getProperty("user.home") + path.substring(1) : path;
    }
}
