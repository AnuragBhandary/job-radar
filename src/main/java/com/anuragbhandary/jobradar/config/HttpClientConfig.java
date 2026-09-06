package com.anuragbhandary.jobradar.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shared {@link HttpClient}.
 *
 * <p>One instance for the whole application: it is thread-safe, and it pools
 * connections, so a new client per board would discard the pooling and open a
 * fresh TLS handshake to the same host forty-six times.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient(AppProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.http().timeoutSeconds()))
                // Several ATS hosts answer the documented URL with a redirect.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
