package com.anuragbhandary.jobradar.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The login page.
 *
 * <p>Hand-written rather than Spring Security's generated one, for the same
 * reason the rest of the UI is: it is the first thing seen and it should look
 * like the tool it belongs to. It is also the one page a stranger could ever
 * reach, so it says as little as possible - no version, no username hint, and the
 * same message whether the username or the password was wrong.
 */
@Controller
public class LoginController {

    private final AuthProperties auth;

    public LoginController(AuthProperties auth) {
        this.auth = auth;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String out,
            HttpServletRequest request) {

        String message = "";
        if (error != null) {
            // One message for both cases. Saying which half was wrong tells an
            // attacker whether the username exists.
            message = "<p class=\"login-error\">That did not work. Try again.</p>";
        } else if (out != null) {
            message = "<p class=\"note-muted\">Signed out.</p>";
        } else if (!auth.isConfigured()) {
            message = """
                    <p class="login-error">No password is set, so nothing will let you in.
                    Run <code>passwd</code> and put the line it prints into
                    <code>~/.config/job-radar/secrets.yml</code>.</p>
                    """;
        }

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());

        String body = """
                <section class="login">
                  <h1>job-radar</h1>
                  <p class="note-muted">This tool can open your inbox, drive a signed-in
                  browser and send an application under your name.</p>
                  %s
                  <form method="post" action="/login">
                    <input type="hidden" name="%s" value="%s">
                    <label for="u">Username</label>
                    <input class="input" type="text" id="u" name="username"
                           autocomplete="username" autofocus required>
                    <label for="p">Password</label>
                    <input class="input" type="password" id="p" name="password"
                           autocomplete="current-password" required>
                    <button class="btn btn-primary" type="submit">Sign in</button>
                  </form>
                </section>
                """.formatted(message,
                        token == null ? "_csrf" : token.getParameterName(),
                        token == null ? "" : token.getToken());

        return Ui.bare("Sign in", body);
    }
}
