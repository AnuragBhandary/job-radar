package com.anuragbhandary.jobradar.web;

import java.util.Locale;

/**
 * Which part of an application a field belongs to.
 *
 * <p>Grouping exists so a person can skip. Eighteen fields listed flat are
 * eighteen things to read; the same eighteen under "Contact details", "Work
 * authorisation" and "Their questions" are three things to read, two of which
 * he already knows the answer to.
 *
 * <p>The groups are the ones a form actually has, taken from the concept ids
 * rather than invented for visual variety. A group nothing falls into is not
 * rendered, so a short form does not grow empty headings.
 */
enum FieldGroup {

    /** Name, email, phone, address, links. Copied, and right by construction. */
    CONTACT("Contact details", "copied from your profile"),

    /** The resume and the letter. */
    DOCUMENTS("Documents", null),

    /**
     * Sponsorship, work authorisation, relocation, remote eligibility.
     *
     * <p>Its own group because these four are the ones that are wrong for one
     * country if answered for another, and because two of them are auto-reject
     * triggers on most boards. Worth a heading he will actually look at.
     */
    AUTHORISATION("Work authorisation", "worked out from where this job is"),

    /** Notice period, start date, salary. The ones that go out of date. */
    AVAILABILITY("Availability and pay", null),

    /** What the employer asked that is not a fact about him. */
    QUESTIONS("Their questions", null),

    /** Self-identification and consent. Answered by declining, on purpose. */
    VOLUNTARY("Voluntary and consent", "left to you, deliberately"),

    /** Anything unclassified. Named honestly rather than hidden in another group. */
    OTHER("Other fields", null);

    private final String label;
    private final String note;

    FieldGroup(String label, String note) {
        this.label = label;
        this.note = note;
    }

    String label() {
        return label;
    }

    /** One line under the heading, where the group has something worth saying. */
    String note() {
        return note;
    }

    /**
     * The group a concept belongs to.
     *
     * <p>Prefixes first, then the handful of ids that do not carry one. An
     * unrecognised question lands in {@link #OTHER}, which is the truth: it is a
     * question nobody has classified, and filing it under a confident heading
     * would say otherwise.
     */
    static FieldGroup of(String conceptId) {
        if (conceptId == null || conceptId.isBlank()) {
            return OTHER;
        }
        String id = conceptId.toLowerCase(Locale.ROOT);
        if (id.startsWith("identity.") || id.startsWith("contact.")
                || id.startsWith("address.") || id.startsWith("link.")) {
            return CONTACT;
        }
        if (id.startsWith("document.")) {
            return DOCUMENTS;
        }
        if (id.startsWith("eeo.") || id.equals("consent")) {
            return VOLUNTARY;
        }
        return switch (id) {
            case "sponsorship.required", "work_authorization", "willing_to_relocate",
                 "remote_eligibility" -> AUTHORISATION;
            case "notice_period", "availability_start", "salary_expectation",
                 "years_of_experience" -> AVAILABILITY;
            case "why_company", "why_role", "previously_applied", "worked_here_before",
                 "related_to_employee", "how_did_you_hear", "education_degree",
                 "age_over_18", "valid_passport", "currently_employed" -> QUESTIONS;
            default -> OTHER;
        };
    }
}
