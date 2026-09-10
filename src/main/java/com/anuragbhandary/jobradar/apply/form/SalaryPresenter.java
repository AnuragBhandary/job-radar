package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How a compensation band should be written into <em>this</em> widget.
 *
 * <p>Split out because the knowledge layer had drifted into deciding it. The
 * question "what is he worth in this country" is knowledge; the question "does
 * this box want 50000, EUR 50,000, or a sentence" is a fact about a web form, and
 * the two were tangled in a single method that consulted the profile and the
 * input's control type in the same breath.
 *
 * <p>The split shows up in the shadow diff: the resolver names the band, the
 * mapper formats it, and the two disagree on the string while agreeing on the
 * money. {@link #isSameIntent} is how that is told apart from a real difference.
 */
public final class SalaryPresenter {

    private SalaryPresenter() {
    }

    /**
     * The string to type.
     *
     * <p>Free-text fields get the "prefer not to say" line by default: naming a
     * number before the employer does is a negotiating loss, and most forms
     * accept a sentence. Numeric fields get the band's target, because they
     * accept nothing else and leaving a required one blank fails the submit.
     */
    public static String render(ApplicantProfile.Compensation compensation,
            ApplicantProfile.Compensation.Band band, FormField field) {

        if (band == null) {
            return null;
        }
        boolean numericOnly = field.control() == FormField.ControlType.TEXT
                && looksNumeric(field.label());
        if (numericOnly || compensation.alwaysStateNumber()) {
            return band.numericAnswer();
        }
        if (field.isFreeText() && compensation.preferNotToSay() != null && !field.required()) {
            return compensation.preferNotToSay();
        }
        return band.textAnswer();
    }

    /**
     * Every string that is a legitimate rendering of one band.
     *
     * <p>Used to tell a formatting difference from a disagreement about money.
     * Two answers drawn from the same band are the same answer however they are
     * spelled, and reporting that as a regression would bury the differences that
     * matter.
     */
    public static Set<String> renderings(ApplicantProfile.Compensation compensation,
            ApplicantProfile.Compensation.Band band) {
        Set<String> all = new LinkedHashSet<>();
        if (band != null) {
            all.add(band.numericAnswer());
            all.add(band.textAnswer());
        }
        if (compensation != null && compensation.preferNotToSay() != null) {
            all.add(compensation.preferNotToSay());
        }
        return all;
    }

    /** True when two answers are the same money written two ways. */
    public static boolean isSameIntent(ApplicantProfile.Compensation compensation,
            ApplicantProfile.Compensation.Band band, String one, String other) {
        if (one == null || other == null) {
            return false;
        }
        Set<String> valid = renderings(compensation, band);
        return valid.contains(one.trim()) && valid.contains(other.trim());
    }

    /** A box that will only accept digits, as far as its label admits. */
    static boolean looksNumeric(String label) {
        String normalised = FieldClassifier.normalise(label);
        return normalised.contains("amount") || normalised.contains("number")
                || normalised.contains("in inr") || normalised.contains("in usd")
                || normalised.contains("in eur") || normalised.contains("annual ctc")
                || normalised.contains("lpa");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
