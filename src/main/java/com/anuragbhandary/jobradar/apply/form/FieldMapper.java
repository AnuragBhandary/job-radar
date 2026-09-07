package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides what to put in a classified field.
 *
 * <p>Pure: profile plus posting plus prepared documents in, {@link Answer} out. No
 * browser, no network, no clock. That is what makes the two dangerous answers on
 * this form testable without a live page.
 *
 * <h2>The two dangerous answers</h2>
 * Work authorisation and sponsorship are asked separately, in opposite polarity,
 * and both depend on where the job is:
 *
 * <pre>
 *   posting country   "authorised to work here?"   "need sponsorship?"
 *   INDIA / REMOTE            yes                         no
 *   anywhere else             no                          yes
 * </pre>
 *
 * REMOTE follows India because a globally-remote role is worked from Mumbai on an
 * Indian contract - no permit is involved. A fixed answer to either question is
 * wrong for one of these two cases every time, and both questions are auto-reject
 * triggers on most boards, so neither is ever stored as a constant.
 */
@Component
public class FieldMapper {

    private final ApplicantProfile profile;

    public FieldMapper(ApplicantProfile profile) {
        this.profile = profile;
    }

    /**
     * The answer for one field, already matched against the field's own options
     * when it is a dropdown or radio group.
     */
    public Answer answer(FormField field, Posting posting, ApplicationDocuments documents) {
        Answer raw = rawAnswer(field, posting, documents);
        if (!field.isChoice()) {
            return raw;
        }
        Answer resolved = toOption(raw, field);

        // The escape hatch applies to every field, not only unrecognised ones.
        // A question this classifies correctly can still offer options no derived
        // answer fits - "select the status that allows you to work and live in
        // that country" is genuinely the authorisation question and its options
        // are three bespoke sentences. Without this, the only way to answer it is
        // to stop having the field classified at all.
        if (resolved.origin() == Answer.Origin.UNANSWERED) {
            Answer configured = fromExtras(field, null);
            if (configured.hasValue()) {
                return toOption(configured, field);
            }
        }
        return resolved;
    }

    /** The answer as text, before it is reconciled with a dropdown's wording. */
    private Answer rawAnswer(FormField field, Posting posting, ApplicationDocuments documents) {
        ApplicantProfile.Name name = profile.name();
        ApplicantProfile.Contact contact = profile.contact();
        ApplicantProfile.Address address = profile.address();
        ApplicantProfile.Demographics eeo = profile.demographics();

        return switch (field.kind()) {
            case FIRST_NAME -> Answer.profile(name.first());
            case MIDDLE_NAME -> Answer.profile(name.middle());
            case LAST_NAME -> Answer.profile(name.last());
            case FULL_NAME -> Answer.profile(name.full());
            case PREFERRED_NAME -> Answer.profile(name.display());

            case EMAIL -> Answer.profile(contact.email());
            case PHONE -> Answer.profile(phoneFor(field));
            case PHONE_COUNTRY_CODE -> Answer.profile(contact.phoneCountryCode());

            case ADDRESS_LINE_1 -> Answer.profile(address.line1());
            case ADDRESS_LINE_2 -> Answer.profile(address.line2());
            case CITY -> Answer.profile(address.city());
            case STATE -> Answer.profile(address.state());
            case POSTAL_CODE -> Answer.profile(address.postalCode());
            case COUNTRY -> Answer.profile(address.country());
            case NATIONALITY -> Answer.profile(address.nationality());

            case LINKEDIN -> Answer.profile(contact.linkedin());
            case GITHUB -> Answer.profile(contact.github());
            case PORTFOLIO -> Answer.profile(contact.portfolio());
            // A second, unlabelled link box. The portfolio is the best thing to
            // put in one, but it may already be filled in elsewhere on the form,
            // so this is flagged rather than filled silently.
            case OTHER_LINK -> Answer.derived(contact.portfolio(),
                    "generic link field - check it is not a duplicate");

            case RESUME_UPLOAD -> Answer.profile(documents.resumePdf().toString());

            // The instruction, and the reason the two are separate kinds: a letter
            // is written for a box, never for an attachment slot.
            case COVER_LETTER_UPLOAD -> Answer.unanswered(
                    "cover letter offered as an upload, not a text box - skipped by design");
            case COVER_LETTER_TEXT -> documents.hasCoverLetter()
                    ? Answer.generated(documents.coverLetter(), "drafted for this posting")
                    : Answer.unanswered("no cover letter was generated");

            case WORK_AUTHORISATION -> authorisation(posting);
            case SPONSORSHIP_REQUIRED -> sponsorship(posting);
            case RELOCATION_WILLING -> Answer.profile(
                    yesNo(profile.workAuthorisation().willRelocate()));

            case SALARY_EXPECTATION -> salary(field, posting);
            case NOTICE_PERIOD -> Answer.profile(profile.availability().noticePeriod());
            case START_DATE -> Answer.profile(profile.availability().earliestStart());

            case GENDER -> optional(eeo.gender());
            case RACE -> optional(eeo.race());
            case HISPANIC_LATINO -> optional(eeo.hispanicOrLatino());
            case VETERAN_STATUS -> optional(eeo.veteranStatus());
            case DISABILITY_STATUS -> optional(eeo.disabilityStatus());
            case PRONOUNS -> optional(eeo.pronouns());

            case REFERRAL_SOURCE -> fromExtras(field, "how did you hear");

            // Never ticked. See FieldKind.CONSENT.
            case CONSENT -> Answer.unanswered(
                    "consent checkbox - tick it yourself after reading it");

            case UNKNOWN -> fromExtras(field, null);
        };
    }

    /**
     * "Are you legally authorised to work in X?"
     *
     * <p>Yes only where no permit is needed. Note this is the answer that must
     * <em>not</em> be optimistic: claiming authorisation the applicant does not
     * have is a false statement on an application, and it surfaces at the offer
     * stage rather than the screening stage - which is much worse.
     */
    private Answer authorisation(Posting posting) {
        Country country = posting.getCountry();
        boolean authorised = profile.workAuthorisation().isAuthorisedIn(country)
                || country == Country.REMOTE;
        return Answer.derived(yesNo(authorised),
                "posting country is " + country + "; authorised = " + authorised);
    }

    /**
     * "Will you now or in the future require sponsorship?"
     *
     * <p>The inverse of {@link #authorisation}, and answered honestly. Saying no
     * to get past a filter produces an interview that ends the moment the question
     * is asked properly, having spent the one application this posting allows.
     */
    private Answer sponsorship(Posting posting) {
        Country country = posting.getCountry();
        boolean needs = !(profile.workAuthorisation().isAuthorisedIn(country)
                || country == Country.REMOTE);
        return Answer.derived(yesNo(needs),
                "posting country is " + country + "; sponsorship needed = " + needs);
    }

    /**
     * The expected-salary answer, in the posting's own currency.
     *
     * <p>Free-text fields get the "prefer not to say" line by default: naming a
     * number before the employer does is a negotiating loss, and most forms accept
     * a sentence. Numeric fields get the band's target, because they accept
     * nothing else and leaving a required one blank fails the submit.
     */
    private Answer salary(FormField field, Posting posting) {
        ApplicantProfile.Compensation comp = profile.compensation();
        ApplicantProfile.Compensation.Band band = comp.bandFor(posting.getCountry());
        if (band == null) {
            return Answer.unanswered("no salary band configured for " + posting.getCountry());
        }

        boolean numericOnly = field.control() == FormField.ControlType.TEXT
                && looksNumeric(field.label());
        if (numericOnly || comp.alwaysStateNumber()) {
            return Answer.derived(band.numericAnswer(),
                    "numeric field; " + band.currency() + " band for " + posting.getCountry());
        }
        if (field.isFreeText() && comp.preferNotToSay() != null && !field.required()) {
            return Answer.derived(comp.preferNotToSay(), "optional free-text salary field");
        }
        return Answer.derived(band.textAnswer(), "band for " + posting.getCountry());
    }

    /** A voluntary field: the configured value, or an explicit decline. */
    private static Answer optional(String value) {
        return value == null || value.isBlank()
                ? Answer.declined("not set in the profile - declining is the intended answer")
                : Answer.profile(value);
    }

    /**
     * The escape hatch: an answer configured by question substring.
     *
     * <p>Everything unrecognised comes here, and almost everything leaves
     * unanswered. That is correct. A required unanswered field stops the run and
     * names the question, which is how the extras map gets its next entry.
     */
    private Answer fromExtras(FormField field, String fallbackKey) {
        String label = FieldClassifier.normalise(field.label());
        for (ApplicantProfile.ExtraAnswer entry : profile.extraAnswers()) {
            // Both sides normalised the same way. The configured match is written
            // as a person would quote the question - with slashes and question
            // marks - and the label has already had those flattened to spaces, so
            // comparing them raw fails on exactly the entries most worth having.
            if (label.contains(FieldClassifier.normalise(entry.match()))) {
                return Answer.profile(entry.answer());
            }
        }
        if (fallbackKey != null) {
            for (ApplicantProfile.ExtraAnswer entry : profile.extraAnswers()) {
                if (entry.match().toLowerCase(Locale.ROOT).contains(fallbackKey)) {
                    return Answer.profile(entry.answer());
                }
            }
        }
        return Answer.unanswered("no configured answer for: " + field.label());
    }

    /**
     * Reconciles a text answer with the options a dropdown actually offers.
     *
     * <p>An unmatched answer becomes UNANSWERED rather than the closest option.
     * On a two-option yes/no field the closest wrong option is the opposite
     * answer, so "closest" is the most dangerous possible fallback.
     */
    private static Answer toOption(Answer answer, FormField field) {
        List<String> options = field.options();
        if (options == null || options.isEmpty()) {
            return answer;
        }

        if (answer.origin() == Answer.Origin.DECLINED) {
            return OptionMatcher.declineOption(options)
                    .map(option -> new Answer(option, Answer.Origin.DECLINED, answer.note()))
                    .orElse(answer);
        }
        if (!answer.hasValue()) {
            return answer;
        }

        Optional<String> matched = OptionMatcher.match(answer.value(), options);
        if (matched.isPresent()) {
            return new Answer(matched.get(), answer.origin(), answer.note());
        }
        // Nothing matched. If the form offers a decline and the question is a
        // voluntary one, that is still better than leaving it untouched.
        return OptionMatcher.declineOption(options)
                .map(option -> new Answer(option, Answer.Origin.DECLINED,
                        "profile value '" + answer.value() + "' matched none of the options"))
                .orElseGet(() -> Answer.unanswered(
                        "'" + answer.value() + "' matched none of: " + String.join(" | ", options)));
    }

    /**
     * Some boards split the country code out and some do not, and there is no
     * label that reliably says which. A field already carrying a country selector
     * beside it rejects "+91..." silently, so the plain number is used unless the
     * label asks for the full international form.
     */
    private String phoneFor(FormField field) {
        String label = FieldClassifier.normalise(field.label());
        boolean wantsInternational = label.contains("country code")
                || label.contains("international") || label.contains("with code");
        return wantsInternational
                ? profile.contact().phoneE164()
                : profile.contact().phoneNumber();
    }

    private static boolean looksNumeric(String label) {
        String normalised = FieldClassifier.normalise(label);
        return normalised.contains("amount") || normalised.contains("number")
                || normalised.contains("in inr") || normalised.contains("in usd")
                || normalised.contains("in eur") || normalised.contains("annual ctc")
                || normalised.contains("lpa");
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }
}
