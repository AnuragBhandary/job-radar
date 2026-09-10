package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The stable facts in {@code applicant.yml}, keyed by concept.
 *
 * <p>A deliberate, temporary duplication of the profile half of
 * {@link com.anuragbhandary.jobradar.apply.form.FieldMapper}'s switch. The old
 * mapper is still the one that fills real forms and is left untouched, so the
 * new resolver needs its own way to read the same file - which is what makes the
 * shadow comparison meaningful: two independent readings of one profile that
 * should agree on every field, and any disagreement is a finding.
 *
 * <p>It goes when the resolver becomes authoritative and {@code FieldMapper}'s
 * switch is deleted, which is the point of running them side by side first.
 */
@Component
public class ProfileFacts {

    private final ApplicantProfile profile;

    public ProfileFacts(ApplicantProfile profile) {
        this.profile = profile;
    }

    /**
     * @return the profile's answer, or empty when this concept is not a profile
     *         field or the field is blank. Blank is empty on purpose: a
     *         PROFILE answer carrying an empty string fills the box with nothing
     *         and reports success, which is worse than saying it is missing.
     */
    public Optional<Fact> valueFor(Concept concept) {
        if (profile == null || concept == null) {
            return Optional.empty();
        }
        String id = concept.id();
        return switch (id) {
            case "identity.first_name" -> fact(name() == null ? null : name().first(),
                    "name.first");
            case "identity.middle_name" -> fact(name() == null ? null : name().middle(),
                    "name.middle");
            case "identity.last_name" -> fact(name() == null ? null : name().last(),
                    "name.last");
            case "identity.full_name" -> fact(name() == null ? null : name().full(),
                    "name");
            case "identity.preferred_name" -> fact(name() == null ? null : name().display(),
                    "name.preferred");
            case "identity.nationality" -> fact(address() == null ? null
                    : address().nationality(), "address.nationality");

            case "contact.email" -> fact(contact() == null ? null : contact().email(),
                    "contact.email");
            case "contact.phone" -> fact(contact() == null ? null : contact().phoneNumber(),
                    "contact.phone-number");
            case "contact.phone_country_code" -> fact(contact() == null ? null
                    : contact().phoneCountryCode(), "contact.phone-country-code");

            case "address.line1" -> fact(address() == null ? null : address().line1(),
                    "address.line1");
            case "address.line2" -> fact(address() == null ? null : address().line2(),
                    "address.line2");
            case "address.city" -> fact(address() == null ? null : address().city(),
                    "address.city");
            case "address.state" -> fact(address() == null ? null : address().state(),
                    "address.state");
            case "address.postal_code" -> fact(address() == null ? null
                    : address().postalCode(), "address.postal-code");
            case "address.country" -> fact(address() == null ? null : address().country(),
                    "address.country");

            case "link.linkedin" -> fact(contact() == null ? null : contact().linkedin(),
                    "contact.linkedin");
            case "link.github" -> fact(contact() == null ? null : contact().github(),
                    "contact.github");
            case "link.portfolio", "link.other" -> fact(contact() == null ? null
                    : contact().portfolio(), "contact.portfolio");

            case "notice_period" -> fact(availability() == null ? null
                    : availability().noticePeriod(), "availability.notice-period");
            case "availability_start" -> fact(availability() == null ? null
                    : availability().earliestStart(), "availability.earliest-start");

            // Voluntary self-identification. A blank one is a decision, not a
            // gap, and the resolver turns it into DECLINED rather than UNKNOWN.
            case "eeo.gender" -> voluntary(eeo() == null ? null : eeo().gender(),
                    "demographics.gender");
            case "eeo.race" -> voluntary(eeo() == null ? null : eeo().race(),
                    "demographics.race");
            case "eeo.hispanic_latino" -> voluntary(eeo() == null ? null
                    : eeo().hispanicOrLatino(), "demographics.hispanic-or-latino");
            case "eeo.veteran" -> voluntary(eeo() == null ? null : eeo().veteranStatus(),
                    "demographics.veteran-status");
            case "eeo.disability" -> voluntary(eeo() == null ? null
                    : eeo().disabilityStatus(), "demographics.disability-status");
            case "eeo.pronouns" -> voluntary(eeo() == null ? null : eeo().pronouns(),
                    "demographics.pronouns");

            default -> Optional.empty();
        };
    }

    /**
     * @param voluntaryDecline the profile deliberately leaves this blank, and
     *                         declining is the intended answer rather than a gap
     */
    public record Fact(String value, List<Evidence> evidence, boolean voluntaryDecline) {
    }

    private static Optional<Fact> fact(String value, String path) {
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(new Fact(value, List.of(Evidence.profile(path, value)), false));
    }

    private static Optional<Fact> voluntary(String value, String path) {
        return value == null || value.isBlank()
                ? Optional.of(new Fact(null,
                        List.of(Evidence.profile(path, "left blank deliberately")), true))
                : Optional.of(new Fact(value, List.of(Evidence.profile(path, value)), false));
    }

    private ApplicantProfile.Name name() {
        return profile.name();
    }

    private ApplicantProfile.Contact contact() {
        return profile.contact();
    }

    private ApplicantProfile.Address address() {
        return profile.address();
    }

    private ApplicantProfile.Demographics eeo() {
        return profile.demographics();
    }

    private ApplicantProfile.Availability availability() {
        return profile.availability();
    }
}
