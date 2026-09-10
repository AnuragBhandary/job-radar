package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything about one application that could change an answer.
 *
 * <p>Immutable, built once per preparation, and the only thing
 * {@link KnowledgeResolver} reads besides the knowledge itself. The resolver
 * never touches a repository for context, a controller or a {@code Posting}:
 * given the same context and the same assertions it returns the same result,
 * which is what makes the sponsorship matrix testable without a browser or a
 * database.
 *
 * <p>Almost every field here arrived in the country and work-mode phase. Before
 * it, the only context available was a five-value enum, and the two questions
 * that matter most - where will the employee actually sit, and who is paying -
 * had nowhere to be answered from.
 *
 * @param remoteEligibleFrom ISO codes a remote employee may sit in.
 *                           <b>Empty means the posting did not say</b>, never
 *                           "anywhere".
 * @param authorisedCountryCodes where he needs no work permit
 * @param homeCountryCode    where he is now
 * @param resumePath         the tailored PDF this preparation rendered, when
 *                           there is one. Not knowledge about him - it is
 *                           produced by the run - but it is still the answer to a
 *                           question the form asks, so the resolver has to be able
 *                           to reach it or it can never replace the mapper.
 * @param coverLetterText    the letter drafted for this posting, likewise
 */
public record ApplicationContext(
        Long postingId,
        String company,
        String boardToken,
        String atsSource,
        String roleTitle,
        String roleFamily,
        String location,
        String countryCode,
        String employerCountryCode,
        WorkMode workMode,
        List<String> remoteEligibleFrom,
        StrategicClass strategicClass,
        Set<String> authorisedCountryCodes,
        String homeCountryCode,
        String sponsorshipSignal,
        String resumePath,
        String coverLetterText) {

    public ApplicationContext {
        remoteEligibleFrom = remoteEligibleFrom == null ? List.of()
                : List.copyOf(remoteEligibleFrom);
        authorisedCountryCodes = authorisedCountryCodes == null ? Set.of()
                : Set.copyOf(authorisedCountryCodes);
        company = company == null ? null : company.trim();
    }

    /**
     * The country whose employment rules govern this job: where the employee will
     * physically be.
     *
     * <p><strong>The single most important method in the knowledge package.</strong>
     * Every context-sensitive answer is scoped and derived against this, not
     * against {@link #countryCode}, and the two differ in exactly the case the
     * whole strategy turns on: a Berlin company hiring into Mumbai is a job whose
     * country is Germany and whose <em>employment</em> country is India. Answering
     * sponsorship from the posting's country gets that one wrong, and it is an
     * auto-reject on most boards.
     *
     * @return an ISO code, or null when the posting has not said enough. Null is
     *         a real answer here and is what stops a bare "Remote" being read as
     *         "remote from India".
     */
    public String employmentCountryCode() {
        if (workMode == null || !workMode.isRemote()) {
            return countryCode;
        }
        if (homeCountryCode != null && remoteEligibleFrom.contains(homeCountryCode)) {
            // The posting says he may sit at home. Nothing else needs consulting.
            return homeCountryCode;
        }
        return switch (workMode) {
            // Remote from anywhere includes here.
            case REMOTE_GLOBAL -> homeCountryCode;
            // Remote, and the posting never said from where. Unknown, and
            // deliberately not resolved to home: reading the bare word "remote"
            // as permission to sit in Mumbai is the inference the work-mode model
            // was built to stop making.
            case REMOTE_UNSPECIFIED -> null;
            // Locked to somewhere, or to a region, that is not home. He has to be
            // there, so that is the country whose rules apply.
            case REMOTE_COUNTRY_LOCKED, REMOTE_REGIONAL -> countryCode;
            default -> countryCode;
        };
    }

    /** True when the posting explicitly said he may work from home country. */
    public boolean statesRemoteFromHome() {
        return homeCountryCode != null && remoteEligibleFrom.contains(homeCountryCode);
    }

    /** True when the posting said nothing about where a remote employee may sit. */
    public boolean remoteScopeUnstated() {
        return workMode != null && workMode.isRemote() && remoteEligibleFrom.isEmpty()
                && workMode != WorkMode.REMOTE_GLOBAL;
    }

    /**
     * Whether he may work in the employment country without a permit.
     *
     * @return empty when the employment country is unknown - which is different
     *         from "no", and is why this is an Optional rather than a boolean
     */
    public java.util.Optional<Boolean> authorisedInEmploymentCountry() {
        String country = employmentCountryCode();
        if (country == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(authorisedCountryCodes.contains(country));
    }

    /** True when taking this job means moving country. */
    public boolean requiresRelocation() {
        String country = employmentCountryCode();
        return country != null && homeCountryCode != null && !country.equals(homeCountryCode)
                && workMode != null && !workMode.isRemote();
    }

    /** A one-line description of the context, for an explanation. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(company == null ? "unknown company" : company);
        if (roleTitle != null) {
            out.append(" / ").append(roleTitle);
        }
        String employment = employmentCountryCode();
        out.append(" · job country ")
                .append(countryCode == null ? "unknown" : CountryCodes.displayName(countryCode));
        out.append(" · employment country ")
                .append(employment == null ? "not stated" : CountryCodes.displayName(employment));
        if (workMode != null) {
            out.append(" · ").append(workMode.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        if (!remoteEligibleFrom.isEmpty()) {
            out.append(" · remote from ").append(String.join("/", remoteEligibleFrom));
        }
        return out.toString();
    }
}
