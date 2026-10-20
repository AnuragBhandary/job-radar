package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.sponsor.SponsorRegister;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code sponsors} shows which visa-sponsor registers are loaded;
 * {@code sponsors --refresh} downloads the UK and Dutch ones again;
 * {@code sponsors --check="Company" [--country=GB]} looks one employer up.
 */
@Component
public class SponsorsCommand {

    private final SponsorRegister register;

    public SponsorsCommand(SponsorRegister register) {
        this.register = register;
    }

    public void run(Map<String, String> options) {
        if (options.containsKey("refresh")) {
            try {
                Map<String, Integer> kept = register.refresh();
                System.out.printf("UK Skilled Worker sponsors: %d · NL recognised sponsors: %d · saved in %s%n",
                        kept.get("GB"), kept.get("NL"), register.dir());
            } catch (Exception e) {
                System.out.println("Refresh failed: " + e.getMessage());
            }
            return;
        }
        if (options.containsKey("check")) {
            String country = options.getOrDefault("country", "GB");
            register.check(country, options.get("check")).ifPresentOrElse(
                    m -> System.out.println(country + ": " + m.status()
                            + (m.registeredName() == null ? "" : " (" + m.registeredName() + ")")),
                    () -> System.out.println(country + " has no sponsor register"));
            return;
        }
        Map<String, Integer> sizes = register.sizes();
        System.out.printf("UK: %d names · NL: %d names · in %s%s%n", sizes.get("GB"), sizes.get("NL"),
                register.dir(), sizes.values().stream().anyMatch(n -> n == 0) ? " (run sponsors --refresh)" : "");
    }
}
