package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DutchRequirementTest {

    @Test
    @DisplayName("a stated requirement rejects, in English or in Dutch")
    void findsRequirement() {
        assertThat(DutchRequirement.find("You have excellent written and spoken Dutch and English."))
                .isPresent();
        assertThat(DutchRequirement.find("Dutch is required for this client-facing role.")).isPresent();
        assertThat(DutchRequirement.find("Je hebt een goede beheersing van de Nederlandse taal.")).isPresent();
    }

    @Test
    @DisplayName("a hedge in the same clause makes it a wish")
    void hedgeCancels() {
        assertThat(DutchRequirement.find("Fluent English required. Fluent Dutch is a plus.")).isEmpty();
        assertThat(DutchRequirement.find("Good Dutch would be nice to have, but English is our language."))
                .isEmpty();
    }

    @Test
    @DisplayName("a posting written in Dutch is detected, and English is not")
    void detectsDutchText() {
        String dutch = ("Wij zoeken een ervaren ontwikkelaar voor ons team. Jij bent verantwoordelijk voor "
                + "het bouwen van onze diensten en je werkt nauw samen met het team. ").repeat(4);
        assertThat(DutchRequirement.isWrittenInDutch(dutch)).isTrue();
        String english = ("We are looking for an engineer to join our team in Amsterdam. You will build "
                + "the services that our customers use and work with the platform team. ").repeat(4);
        assertThat(DutchRequirement.isWrittenInDutch(english)).isFalse();
    }

    @Test
    @DisplayName("Spanish and French are not taken for Dutch")
    void otherLanguages() {
        String spanish = ("Buscamos un ingeniero en Madrid con experiencia en sistemas en la nube y en "
                + "equipos distribuidos en Europa. ").repeat(6);
        assertThat(DutchRequirement.find(spanish)).isEmpty();
    }
}
