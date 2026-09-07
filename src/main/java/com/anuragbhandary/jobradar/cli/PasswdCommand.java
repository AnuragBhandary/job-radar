package com.anuragbhandary.jobradar.cli;

import java.io.Console;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@code passwd} - print a bcrypt line to paste into secrets.yml.
 *
 * <p>Exists so setting a password does not require finding a bcrypt tool, and so
 * the plaintext never has to be typed into a file that something might read back.
 * It reads through {@link Console#readPassword()} where there is a console, which
 * does not echo and does not leave the password in a shell history.
 *
 * <p>It prints the hash and does not write it. The profile is hand-maintained and
 * holds everything the tool knows about its user; a command that edits it in
 * place to set a password is a command that can corrupt it while doing so.
 */
@Component
public class PasswdCommand {

    /** Short enough to type often, long enough to be worth typing. */
    private static final int MINIMUM_LENGTH = 12;

    public void run(Map<String, String> options) {
        Console console = System.console();
        if (console == null) {
            System.out.println("""
                    passwd needs a terminal, and this is not one.

                    Run it directly rather than through a pipe or an IDE:
                      mvn -q spring-boot:run -Dspring-boot.run.arguments=passwd
                    """);
            return;
        }

        char[] first = console.readPassword("New password: ");
        if (first == null || first.length < MINIMUM_LENGTH) {
            java.util.Arrays.fill(first == null ? new char[0] : first, ' ');
            System.out.printf("Too short. Use at least %d characters.%n", MINIMUM_LENGTH);
            return;
        }

        char[] again = console.readPassword("Again: ");
        if (again == null || !java.util.Arrays.equals(first, again)) {
            java.util.Arrays.fill(first, ' ');
            java.util.Arrays.fill(again == null ? new char[0] : again, ' ');
            System.out.println("They do not match. Nothing changed.");
            return;
        }

        String hash = new BCryptPasswordEncoder().encode(new String(first));
        // Cleared rather than left for the garbage collector to get to eventually.
        java.util.Arrays.fill(first, ' ');
        java.util.Arrays.fill(again, ' ');

        System.out.printf("""

                Put this in ~/.config/job-radar/secrets.yml, under job-radar:

                  auth:
                    username: %s
                    password-hash: "%s"

                Then restart `ui`. The hash is not a secret in the way the password
                is, but the file is chmod 600 and gitignored anyway.
                """, options.getOrDefault("user", "me"), hash);
    }
}
