package com.anuragbhandary.jobradar.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.FileDataStoreFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The states the button has to render, without going near Google.
 *
 * <p>Consent itself cannot be tested here: it opens a browser and waits for a
 * human. What can be tested is everything decided from what is on disk, which is
 * where the mistakes would actually be.
 */
class GmailConnectionTest {

    @TempDir
    Path tokens;

    /** Configured, in the sense that a client secret exists. Contents never parsed. */
    private Path secret(Path dir) throws IOException {
        Path file = dir.resolve("gmail-oauth.json");
        Files.writeString(file, "{}");
        return file;
    }

    private GmailClient client(String secretPath) {
        return new GmailClient(new GmailProperties(secretPath, tokens.toString(), 60));
    }

    @Test
    @DisplayName("no client secret means there is nothing to offer but instructions")
    void unconfigured() {
        GmailConnection.Status status =
                new GmailConnection(client("/nowhere/gmail-oauth.json")).status();

        assertThat(status.state()).isEqualTo(GmailConnection.State.UNCONFIGURED);
        assertThat(status.detail()).contains("gmail-oauth.json");
        assertThat(status.busy()).isFalse();
    }

    @Test
    @DisplayName("a client secret with no approval yet reads as disconnected")
    void disconnected(@TempDir Path home) throws IOException {
        GmailConnection connection = new GmailConnection(client(secret(home).toString()));

        assertThat(connection.status().state()).isEqualTo(GmailConnection.State.DISCONNECTED);
    }

    @Test
    @DisplayName("an approval on disk reads as connected, with when")
    void connected(@TempDir Path home) throws IOException {
        GmailClient gmail = client(secret(home).toString());
        store().set("user", new StoredCredential());

        GmailConnection.Status status = new GmailConnection(gmail).status();

        assertThat(status.state()).isEqualTo(GmailConnection.State.CONNECTED);
        assertThat(status.since()).isPresent();
        assertThat(status.since().orElseThrow())
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("a store file with no entry is not a connection")
    void anEmptyStoreIsNotAToken(@TempDir Path home) throws IOException {
        // The datastore writes its file the moment it is opened, so the file
        // appears when a consent flow starts and long before anyone approves.
        // Treating the file as the answer reports "connected" to someone who
        // walked away from the Google page.
        store();
        assertThat(Files.exists(tokens.resolve("StoredCredential"))).isTrue();

        assertThat(new GmailConnection(client(secret(home).toString())).status().state())
                .isEqualTo(GmailConnection.State.DISCONNECTED);
    }

    @Test
    @DisplayName("connecting with no client secret refuses instead of opening a browser")
    void connectNeedsAClient() {
        GmailConnection connection = new GmailConnection(client("/nowhere/gmail-oauth.json"));

        assertThat(connection.connect()).contains("no OAuth client");
        assertThat(connection.status().state()).isEqualTo(GmailConnection.State.UNCONFIGURED);
    }

    @Test
    @DisplayName("disconnecting removes the approval and says so")
    void disconnect(@TempDir Path home) throws IOException {
        GmailClient gmail = client(secret(home).toString());
        store().set("user", new StoredCredential());
        GmailConnection connection = new GmailConnection(gmail);
        assertThat(connection.status().state()).isEqualTo(GmailConnection.State.CONNECTED);

        assertThat(connection.disconnect()).contains("Disconnected");
        assertThat(connection.status().state()).isEqualTo(GmailConnection.State.DISCONNECTED);
    }

    @Test
    @DisplayName("disconnecting works even when the client secret has gone missing")
    void disconnectWithoutTheClient() throws IOException {
        // One of the states you would be disconnecting in.
        store().set("user", new StoredCredential());
        GmailConnection connection = new GmailConnection(client("/nowhere/gmail-oauth.json"));

        assertThat(connection.disconnect()).contains("Disconnected");
        assertThat(client("/nowhere/gmail-oauth.json").hasToken()).isFalse();
    }

    private com.google.api.client.util.store.DataStore<StoredCredential> store()
            throws IOException {
        return new FileDataStoreFactory(tokens.toFile())
                .getDataStore(StoredCredential.DEFAULT_DATA_STORE_ID);
    }
}
