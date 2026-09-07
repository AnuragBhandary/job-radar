package com.anuragbhandary.jobradar.mail;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
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

    private final GmailProperties config;
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
        try {
            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            GsonFactory json = GsonFactory.getDefaultInstance();

            GoogleClientSecrets secrets;
            try (FileReader reader = new FileReader(expand(config.credentialsPath()))) {
                secrets = GoogleClientSecrets.load(json, reader);
            }

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport, json, secrets, List.of(GmailScopes.GMAIL_READONLY))
                    .setDataStoreFactory(new FileDataStoreFactory(
                            new File(expand(config.tokensDir()))))
                    .setAccessType("offline")
                    .build();

            Credential credential = new AuthorizationCodeInstalledApp(
                    flow, new LocalServerReceiver.Builder().setPort(8888).build())
                    .authorize("user");

            cached = new Gmail.Builder(transport, json, credential)
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
