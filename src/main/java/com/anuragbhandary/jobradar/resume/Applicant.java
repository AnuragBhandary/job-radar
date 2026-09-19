package com.anuragbhandary.jobradar.resume;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The resume header: name and contact lines, from {@code applicant.yml}.
 *
 * <p>Only what a resume prints. The same file holds form answers from the earlier
 * form-filling code (tag {@code v1-full}); they are left in the file and not bound.
 */
@ConfigurationProperties(prefix = "job-radar.applicant")
public record Applicant(Name name, Contact contact, Address address) {

    public record Name(String first, String middle, String last) {

        public String full() {
            StringBuilder sb = new StringBuilder(first == null ? "" : first);
            if (middle != null && !middle.isBlank()) {
                sb.append(' ').append(middle);
            }
            return sb.append(' ').append(last == null ? "" : last).toString().trim();
        }
    }

    public record Contact(String email, String phoneCountryCode, String phoneNumber,
            String linkedin, String github, String portfolio) {

        public String phoneE164() {
            if (phoneNumber == null) {
                return null;
            }
            return (phoneCountryCode == null ? "" : phoneCountryCode)
                    + phoneNumber.replaceAll("\\s+", "");
        }
    }

    /** City and country only are printed; a street address is not a resume's business. */
    public record Address(String city, String country) {
    }
}
