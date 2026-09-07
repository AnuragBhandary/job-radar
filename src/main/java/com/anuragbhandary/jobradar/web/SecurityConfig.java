package com.anuragbhandary.jobradar.web;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Who may open the review queue.
 *
 * <p>Only built when the web layer is on, which is only the {@code ui} command -
 * {@code main()} selects {@code WebApplicationType.NONE} for the other twelve, so
 * no filter chain exists while {@code fetch} is running.
 *
 * <h2>Why a password and not "sign in with Google"</h2>
 * Google sign-in is the better answer when there are users to tell apart, and
 * there is one user here. It also costs more than it looks: an OAuth client, a
 * registered redirect URI, and - the part that matters - <strong>a publicly
 * reachable callback URL</strong>. Google has to be able to send the browser back
 * somewhere, which pushes towards exposing this to the internet, and exposing
 * this to the internet is the thing worth avoiding. A password plus a private
 * network keeps the whole problem smaller.
 *
 * <h2>What a login does and does not protect</h2>
 * It stops someone who reaches the port from using the app. It does nothing about
 * the machine itself, and the machine is where the real value sits: a Chromium
 * profile holding live session cookies for every job board signed into, a Gmail
 * refresh token, a Google service-account key, and a database of applications.
 * A login is worth having and is not what makes this safe to host somewhere else.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The single account, from config.
     *
     * <p>When no password is set the account is still created, with a hash that
     * nothing matches, so an unconfigured instance refuses every login rather than
     * accepting any. {@link #refuseToStartUnprotectedOnAPublicPort} decides
     * whether an unconfigured instance may run at all.
     */
    @Bean
    public UserDetailsService users(AuthProperties auth) {
        String hash = auth.isConfigured()
                ? auth.passwordHash()
                // A valid bcrypt hash of a value nobody has. Not the empty string:
                // an unparseable hash makes the encoder throw on every attempt,
                // and a stack trace per login is a worse failure than a refusal.
                : new BCryptPasswordEncoder().encode(
                        java.util.UUID.randomUUID().toString());

        return new InMemoryUserDetailsManager(
                User.withUsername(auth.username()).password(hash).roles("USER").build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthProperties auth)
            throws Exception {

        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(new AntPathRequestMatcher("/login")).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        // Always land on the queue. Spring's default is to resume
                        // the request that triggered the login, which after a
                        // session timeout means a POST to /submit replaying itself
                        // the moment the password is typed.
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?out"))
                // CSRF stays on. Every state-changing endpoint here starts a
                // browser, sends an application, or writes to applicant.yml, and
                // all of them are reachable by a form post.
                .csrf(csrf -> { })
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                // The pages carry small inline scripts and one
                                // inline stylesheet, both written here. Nothing is
                                // fetched from anywhere: no CDN, no webfont, and
                                // with form-action self a stolen page cannot post
                                // an application somewhere else.
                                "default-src 'self'; img-src 'self' data:; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "form-action 'self'; frame-ancestors 'none'")))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.newSession())
                        .maximumSessions(2));

        return http.build();
    }

    /**
     * Refuses to start on a public port without a password.
     *
     * <p>The dangerous configuration is not "no password" - on loopback that is
     * the tool as it has always been - and it is not "reachable" either. It is the
     * two together, and it is easy to arrive at by editing one line of YAML. So
     * the check is on the pair, it runs at startup, and it stops the process
     * rather than logging something.
     */
    @Bean
    public ServletWebServerFactory refuseToStartUnprotectedOnAPublicPort(
            AuthProperties auth,
            org.springframework.boot.autoconfigure.web.ServerProperties server) {

        String address = server.getAddress() == null
                ? "127.0.0.1" : server.getAddress().getHostAddress();
        boolean loopback = server.getAddress() == null || server.getAddress().isLoopbackAddress();

        if (!auth.isConfigured()) {
            if (!loopback) {
                throw new IllegalStateException("""

                        job-radar refuses to start.

                          It is bound to %s, which is reachable from other machines,
                          and no password is set.

                          This UI can drive a browser that is signed in to your job
                          boards, read your inbox, and send an application under your
                          name. Run `passwd` and put the line it prints into
                          ~/.config/job-radar/secrets.yml, or bind to 127.0.0.1.
                        """.formatted(address));
            }
            log.warn("No password set. This is fine on 127.0.0.1 and nowhere else - "
                    + "run `passwd` before exposing the port.");
        }

        var factory = new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory();
        factory.getSession().setTimeout(Duration.ofHours(auth.sessionHours()));
        return factory;
    }
}
