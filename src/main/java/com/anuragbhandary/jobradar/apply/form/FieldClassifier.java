package com.anuragbhandary.jobradar.apply.form;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Turns a form's own wording into a {@link FieldKind}.
 *
 * <p>Rules are ordered and the first match wins, so the specific ones come first:
 * "First name" has to be tested before "name", and "Do you require sponsorship"
 * before anything matching "work". Every rule is a substring test on the
 * lowercased, punctuation-flattened label, which is the same matching the
 * screening filters use.
 *
 * <p>Nothing here is fuzzy and nothing is a model. A wrong answer on an
 * application form is not recoverable - most ATSs allow one application per
 * posting, ever - so a label this class does not recognise becomes
 * {@link FieldKind#UNKNOWN} and a human is asked. Guessing is the failure mode
 * being designed against, not the feature.
 */
@Component
public class FieldClassifier {

    /** One ordered rule: if the label matches, it is this kind. */
    private record Rule(FieldKind kind, Predicate<String> matches) {
    }

    /**
     * Order is load-bearing. Read this list top to bottom the way the classifier
     * does; moving a rule up or down changes what other rules ever see.
     */
    private static final List<Rule> RULES = List.of(

            // --- The two authorisation questions, first, and in this order. ------
            //
            // They share vocabulary with each other and with half the form, so
            // they have to be settled before any looser rule runs.
            //
            // "...authorized to work in the US WITHOUT sponsorship?" is a single
            // combined question, and its polarity follows authorisation, not
            // sponsorship: yes means "I need nothing from you". Classifying it as
            // SPONSORSHIP_REQUIRED inverts the answer, which is an auto-reject.
            new Rule(FieldKind.WORK_AUTHORISATION,
                    l -> contains(l, "without sponsorship")
                            || contains(l, "without the need for sponsorship")
                            || contains(l, "without requiring sponsorship")),

            new Rule(FieldKind.SPONSORSHIP_REQUIRED,
                    l -> contains(l, "sponsorship") || contains(l, "sponsor")
                            || contains(l, "visa support") || contains(l, "work permit")
                            || contains(l, "immigration support")),

            new Rule(FieldKind.WORK_AUTHORISATION,
                    l -> contains(l, "legally authorized") || contains(l, "legally authorised")
                            || contains(l, "authorized to work") || contains(l, "authorised to work")
                            || contains(l, "right to work") || contains(l, "eligible to work")
                            || contains(l, "work authorization") || contains(l, "work authorisation")
                            || contains(l, "legally entitled to work")),

            new Rule(FieldKind.RELOCATION_WILLING,
                    l -> contains(l, "relocat")),

            // --- Documents. Upload and text box are different questions. --------
            //
            // The split matters: a letter is written only where there is a box to
            // type it into. An optional attachment slot gets nothing.
            new Rule(FieldKind.RESUME_UPLOAD,
                    l -> contains(l, "resume") || contains(l, "resumé") || word(l, "cv")
                            || contains(l, "curriculum vitae")),

            new Rule(FieldKind.COVER_LETTER_UPLOAD,
                    l -> contains(l, "cover letter") || contains(l, "covering letter")
                            || contains(l, "motivation letter") || contains(l, "motivational letter")),

            // --- Name. Specific before general, or "name" swallows all four. ----
            new Rule(FieldKind.FIRST_NAME,
                    l -> contains(l, "first name") || contains(l, "given name")
                            || contains(l, "forename")),
            new Rule(FieldKind.MIDDLE_NAME,
                    l -> contains(l, "middle name") || contains(l, "middle initial")),
            new Rule(FieldKind.LAST_NAME,
                    l -> contains(l, "last name") || contains(l, "surname")
                            || contains(l, "family name")),
            new Rule(FieldKind.PREFERRED_NAME,
                    l -> contains(l, "preferred name") || contains(l, "nickname")
                            || contains(l, "what should we call you")),
            new Rule(FieldKind.PRONOUNS,
                    l -> contains(l, "pronoun")),
            new Rule(FieldKind.FULL_NAME,
                    l -> equalsAny(l, "name", "your name", "full name", "legal name")
                            || contains(l, "full name") || contains(l, "full legal name")),

            // --- Contact ---------------------------------------------------------
            new Rule(FieldKind.EMAIL,
                    l -> contains(l, "email") || contains(l, "e-mail")),
            new Rule(FieldKind.PHONE_COUNTRY_CODE,
                    l -> contains(l, "country code") || contains(l, "dial code")
                            || contains(l, "phone code")),
            new Rule(FieldKind.PHONE,
                    l -> contains(l, "phone") || contains(l, "mobile")
                            || contains(l, "telephone") || contains(l, "contact number")),

            // --- Links. Before ADDRESS, because "LinkedIn URL" contains no
            //     address word but "Website" would fall through to OTHER_LINK. --
            new Rule(FieldKind.LINKEDIN,
                    l -> contains(l, "linkedin")),
            new Rule(FieldKind.GITHUB,
                    l -> contains(l, "github") || contains(l, "gitlab")),
            new Rule(FieldKind.PORTFOLIO,
                    l -> contains(l, "portfolio") || contains(l, "personal website")
                            || contains(l, "personal site") || contains(l, "your website")),
            // A bare "Website" or "Other link" box. Answered with the portfolio,
            // but flagged for review rather than filled silently - on a form that
            // already has a dedicated portfolio field this one is a duplicate.
            new Rule(FieldKind.OTHER_LINK,
                    l -> equalsAny(l, "website", "url", "link", "other link",
                            "other website", "additional links")),

            // --- Address ---------------------------------------------------------
            //
            // "address" alone must not run before EMAIL: "Email address" is the
            // most common label on the form and would become a street address.
            // EMAIL is above, so by the time this fires the email is spoken for.
            new Rule(FieldKind.ADDRESS_LINE_2,
                    l -> contains(l, "address line 2") || contains(l, "address 2")
                            || contains(l, "apartment") || contains(l, "suite")
                            || contains(l, "unit number")),
            new Rule(FieldKind.ADDRESS_LINE_1,
                    l -> contains(l, "address line 1") || contains(l, "address 1")
                            || contains(l, "street address") || contains(l, "street")
                            || equalsAny(l, "address", "your address", "home address",
                                    "current address", "mailing address")),
            new Rule(FieldKind.POSTAL_CODE,
                    l -> contains(l, "postal code") || contains(l, "post code")
                            || contains(l, "postcode") || word(l, "zip")
                            || contains(l, "pin code") || contains(l, "pincode")),
            new Rule(FieldKind.CITY,
                    l -> word(l, "city") || word(l, "town")
                            // "Location" on an application form is where the
                            // applicant is, not where the job is.
                            || equalsAny(l, "location", "current location", "your location")),
            new Rule(FieldKind.STATE,
                    l -> word(l, "state") && !contains(l, "united states")
                            || contains(l, "province") || word(l, "region")),
            new Rule(FieldKind.NATIONALITY,
                    l -> contains(l, "nationality") || contains(l, "citizenship")
                            || contains(l, "citizen of")),
            new Rule(FieldKind.COUNTRY,
                    l -> contains(l, "country")),

            // --- Money and dates -------------------------------------------------
            new Rule(FieldKind.SALARY_EXPECTATION,
                    l -> contains(l, "salary") || contains(l, "compensation")
                            || contains(l, "expected pay") || contains(l, "pay expectation")
                            || contains(l, "desired pay") || contains(l, "remuneration")
                            || word(l, "ctc")),
            new Rule(FieldKind.NOTICE_PERIOD,
                    l -> contains(l, "notice period") || word(l, "notice")),
            new Rule(FieldKind.START_DATE,
                    l -> contains(l, "start date") || contains(l, "available to start")
                            || contains(l, "availability") || contains(l, "earliest")
                            || contains(l, "when can you start")),

            // --- Voluntary self-identification ----------------------------------
            //
            // HISPANIC_LATINO before RACE: the US EEO form asks it as its own
            // question, and "Hispanic or Latino" also appears inside the race
            // options - so a "race" rule placed first answers the wrong one.
            new Rule(FieldKind.HISPANIC_LATINO,
                    l -> contains(l, "hispanic") || contains(l, "latino")
                            || contains(l, "latinx")),
            new Rule(FieldKind.VETERAN_STATUS,
                    l -> contains(l, "veteran") || contains(l, "military")
                            || contains(l, "armed forces")),
            new Rule(FieldKind.DISABILITY_STATUS,
                    l -> contains(l, "disability") || contains(l, "disabled")
                            || contains(l, "differently abled")),
            new Rule(FieldKind.GENDER,
                    // "sex" as a whole word: it is inside "sexual orientation",
                    // which some boards ask as a separate question.
                    l -> contains(l, "gender") || word(l, "sex")),
            new Rule(FieldKind.RACE,
                    l -> word(l, "race") || contains(l, "ethnicity") || contains(l, "ethnic")),

            new Rule(FieldKind.REFERRAL_SOURCE,
                    l -> contains(l, "how did you hear") || contains(l, "how did you find")
                            || contains(l, "referral") || contains(l, "referred by")
                            || word(l, "source"))
    );

    /**
     * Classifies one field.
     *
     * <p>The control type is consulted only for cover letters, where upload and
     * text box carry different instructions and the label is identical.
     */
    public FieldKind classify(FormField field) {
        String label = normalise(field.label());
        if (label.isBlank()) {
            return FieldKind.UNKNOWN;
        }

        for (Rule rule : RULES) {
            if (rule.matches().test(label)) {
                return refine(rule.kind(), field);
            }
        }
        return FieldKind.UNKNOWN;
    }

    /**
     * The one place the widget overrules the wording.
     *
     * <p>"Cover letter" on a file input is an attachment; the same words on a
     * textarea are a box to write in. Greenhouse renders both, sometimes on the
     * same form, and the instruction differs between them.
     */
    private static FieldKind refine(FieldKind kind, FormField field) {
        if (kind == FieldKind.COVER_LETTER_UPLOAD
                && field.control() == FormField.ControlType.TEXTAREA) {
            return FieldKind.COVER_LETTER_TEXT;
        }
        if (kind == FieldKind.RESUME_UPLOAD
                && field.control() == FormField.ControlType.TEXTAREA) {
            // Lever offers a "paste your resume instead" textarea beside the
            // upload. Filling both duplicates the document; the upload wins, so
            // this becomes something the tool leaves alone.
            return FieldKind.UNKNOWN;
        }
        return kind;
    }

    /**
     * Lowercase, strip punctuation to spaces, collapse whitespace.
     *
     * <p>Punctuation is flattened rather than deleted so that "e-mail" does not
     * become "email" by accident in one place and "e mail" in another - both are
     * matched explicitly instead. The asterisk that marks a required field and
     * the trailing colon are the two most common differences between the same
     * question on two boards.
     *
     * <p>Public because {@link com.anuragbhandary.jobradar.apply.OpenQuestion}
     * builds profile keys with it. A key normalised differently from the label it
     * has to match is a key that never fires, so there is exactly one definition
     * of "the same question".
     */
    public static String normalise(String label) {
        if (label == null) {
            return "";
        }
        return label.toLowerCase(Locale.ROOT)
                .replace(' ', ' ')
                .replaceAll("[*:_/\\\\()\\[\\],.?!\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean contains(String label, String needle) {
        return label.contains(needle);
    }

    /**
     * Whole-word containment, for the tokens short enough to hide inside other
     * words.
     *
     * <p>Written because "Race / Ethnicity" classified as CITY: "ethnicity"
     * contains "city". This is the same bug as "distributed" matching
     * "Distributed Systems" in the geography filter, and it fails the same way -
     * quietly, as a field filled with the wrong thing rather than an error. The
     * rule that came out of it: a matcher token that is also a fragment of common
     * English needs a boundary, and the short ones always are.
     *
     * <p>Labels are already punctuation-flattened by {@link #normalise}, so a
     * boundary here is any non-alphanumeric character.
     */
    private static boolean word(String label, String needle) {
        int from = 0;
        while (true) {
            int at = label.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(label.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end == label.length()
                    || !Character.isLetterOrDigit(label.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean equalsAny(String label, String... candidates) {
        for (String candidate : candidates) {
            if (label.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
