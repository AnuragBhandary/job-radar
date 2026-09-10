package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds the immutable context the resolver reads.
 *
 * <p>One place that knows how to turn a {@link Posting}, a board and the profile
 * into an {@link ApplicationContext}, so the resolver never has to reach for a
 * repository and every caller gets the same context for the same posting.
 */
@Component
public class ApplicationContextFactory {

    private final BoardTokenRepository boards;
    private final ApplicantProfile profile;
    private final CountryStrategy strategy;

    public ApplicationContextFactory(BoardTokenRepository boards, ApplicantProfile profile,
            CountryStrategy strategy) {
        this.boards = boards;
        this.profile = profile;
        this.strategy = strategy;
    }

    public ApplicationContext of(Posting posting) {
        return of(posting, companyName(posting), null, null);
    }

    /** With the documents this preparation has already rendered. */
    public ApplicationContext of(Posting posting,
            com.anuragbhandary.jobradar.apply.ApplicationDocuments documents) {
        return of(posting, companyName(posting),
                documents == null || documents.resumePdf() == null
                        ? null : documents.resumePdf().toString(),
                documents == null ? null : documents.coverLetter());
    }

    public ApplicationContext of(Posting posting, String company,
            String resumePath, String coverLetterText) {
        if (posting == null) {
            return empty();
        }
        return new ApplicationContext(
                posting.getId(),
                company,
                posting.getBoardToken(),
                posting.getSource() == null ? null : posting.getSource().name(),
                posting.getTitle(),
                roleFamily(posting.getTitle()),
                posting.getLocation(),
                posting.getCountryCode(),
                posting.getEmployerCountryCode(),
                posting.getWorkMode(),
                posting.remoteEligibleFromCodes(),
                posting.getStrategicClass(),
                authorisedCountryCodes(),
                strategy.homeCountry(),
                posting.getSponsorshipSignal(),
                resumePath,
                coverLetterText);
    }

    /** A context with nothing in it but the applicant. For tests and for the CLI. */
    public ApplicationContext empty() {
        return new ApplicationContext(null, null, null, null, null, null, null, null, null,
                null, java.util.List.of(), null, authorisedCountryCodes(),
                strategy.homeCountry(), null, null, null);
    }

    /**
     * The countries he may work in without a permit, as ISO codes.
     *
     * <p>Read from the existing {@code authorised-in: [INDIA]} list, which binds
     * to the legacy {@link Country} enum. Mapped rather than migrated: the
     * profile is hand-maintained personal data outside the repository, and
     * changing its shape to suit an internal refactor is not a trade worth
     * making. {@code REMOTE} and {@code OTHER} have no country and are dropped,
     * which is correct - neither is a place he holds a permit for.
     */
    private Set<String> authorisedCountryCodes() {
        Set<String> codes = new LinkedHashSet<>();
        if (profile != null && profile.workAuthorisation() != null
                && profile.workAuthorisation().authorisedIn() != null) {
            for (Country country : profile.workAuthorisation().authorisedIn()) {
                String code = CountryCodes.fromLegacy(country);
                if (code != null) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private String companyName(Posting posting) {
        if (posting == null) {
            return null;
        }
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    /**
     * A coarse discipline label from the title.
     *
     * <p>Carried for grounding and for later use. Deliberately <em>not</em> a
     * scope level: no concept needs one, and a level nothing defaults to only
     * makes the specificity ordering harder to reason about.
     */
    static String roleFamily(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("data engineer") || lower.contains("analytics")) {
            return "data";
        }
        if (lower.contains("machine learning") || lower.contains("ml ")
                || lower.contains("ai engineer") || lower.contains("data scientist")) {
            return "ml";
        }
        if (lower.contains("frontend") || lower.contains("front-end")) {
            return "frontend";
        }
        if (lower.contains("full stack") || lower.contains("fullstack")
                || lower.contains("full-stack")) {
            return "fullstack";
        }
        if (lower.contains("platform") || lower.contains("infrastructure")
                || lower.contains("devops") || lower.contains("reliability")) {
            return "platform";
        }
        return "backend";
    }
}
