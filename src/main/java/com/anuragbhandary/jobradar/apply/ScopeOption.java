package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.knowledge.Scope;
import java.util.Locale;

/**
 * One place an answer could be saved, described in words the applicant can check.
 *
 * <p>"Remember this?" is the question the old system asked, and it is unanswerable:
 * remember it where? A sponsorship answer given for a German role and remembered
 * everywhere is an auto-reject on the next Indian one. So the choice is offered as
 * a sentence naming the actual boundary - "for future applications in Germany" -
 * and the boundary comes from the backend, which is also the thing that enforces
 * it.
 *
 * @param recommended the concept's own default, shown first. A picker whose first
 *                    option is the wrong one is a picker that gets accepted unread.
 */
public record ScopeOption(
        String level, String value, String label, String sentence, boolean recommended) {

    public static ScopeOption of(Scope scope, boolean recommended) {
        return new ScopeOption(scope.level().name(), scope.value(),
                label(scope), sentence(scope), recommended);
    }

    /** The short name, for a radio button. */
    private static String label(Scope scope) {
        return switch (scope.level()) {
            case APPLICATION -> "This application only";
            case COMPANY -> "Every application to " + scope.value();
            case COUNTRY -> "Every job in " + CountryCodes.displayName(scope.value());
            case WORK_MODE -> "Every " + readable(scope.value()) + " job";
            case STRATEGIC_CLASS -> "Every " + readable(scope.value()) + " opportunity";
            case GLOBAL -> "Everywhere";
        };
    }

    /** The question, spelled out, so what is about to be saved is not a guess. */
    private static String sentence(Scope scope) {
        return switch (scope.level()) {
            case APPLICATION -> "Use this answer here and nowhere else.";
            case COMPANY -> "Save this answer for future applications to "
                    + scope.value() + "?";
            case COUNTRY -> "Save this answer for future applications in "
                    + CountryCodes.displayName(scope.value()) + "?";
            case WORK_MODE -> "Save this answer for future "
                    + readable(scope.value()) + " roles?";
            case STRATEGIC_CLASS -> "Save this answer for every "
                    + readable(scope.value()) + " opportunity?";
            case GLOBAL -> "Save this answer for every application, everywhere?";
        };
    }

    private static String readable(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
