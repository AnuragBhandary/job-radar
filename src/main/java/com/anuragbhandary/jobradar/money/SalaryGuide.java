package com.anuragbhandary.jobradar.money;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The number to write in the salary box for a given posting.
 *
 * <p>Reads the band already on the profile rather than inventing a second
 * opinion. That band is what the form filler types into a salary field, so a
 * separate calculation here would mean the screen and the form could disagree,
 * and the screen is the one being trusted to prepare someone for a conversation.
 */
@Service
public class SalaryGuide {

    private final ApplicantProfile profile;
    private final MoneyProperties money;

    public SalaryGuide(ApplicantProfile profile, MoneyProperties money) {
        this.profile = profile;
        this.money = money;
    }

    public Optional<AskingPrice> forPosting(Posting posting) {
        return forCountry(posting == null ? null : posting.getCountry());
    }

    public Optional<AskingPrice> forCountry(Country country) {
        if (profile == null || profile.compensation() == null) {
            return Optional.empty();
        }
        ApplicantProfile.Compensation.Band band =
                profile.compensation().bandFor(country == null ? Country.OTHER : country);
        if (band == null || band.minimum() == null || band.target() == null) {
            return Optional.empty();
        }
        return Optional.of(new AskingPrice(
                band.currency(), band.minimum(), band.target(),
                money.rateFor(band.currency()), money.ratesAs()));
    }
}
