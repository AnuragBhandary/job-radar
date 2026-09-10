package com.anuragbhandary.jobradar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScopeTest {

    @Test
    @DisplayName("specificity runs application to global, and the ordering is the contract")
    void specificityOrdering() {
        assertThat(Scope.Level.APPLICATION.isMoreSpecificThan(Scope.Level.COMPANY)).isTrue();
        assertThat(Scope.Level.COMPANY.isMoreSpecificThan(Scope.Level.COUNTRY)).isTrue();
        assertThat(Scope.Level.COUNTRY.isMoreSpecificThan(Scope.Level.WORK_MODE)).isTrue();
        assertThat(Scope.Level.WORK_MODE.isMoreSpecificThan(Scope.Level.STRATEGIC_CLASS))
                .isTrue();
        assertThat(Scope.Level.STRATEGIC_CLASS.isMoreSpecificThan(Scope.Level.GLOBAL)).isTrue();
        assertThat(Scope.Level.GLOBAL.isMoreSpecificThan(Scope.Level.APPLICATION)).isFalse();
    }

    @Test
    @DisplayName("global applies everywhere, including to no context at all")
    void globalApplies() {
        assertThat(Scope.global().appliesTo(Contexts.germanyOnsite())).isTrue();
        assertThat(Scope.global().appliesTo(null)).isTrue();
    }

    @Test
    @DisplayName("a country scope matches the employment country, not the posting's")
    void countryScopeUsesEmploymentCountry() {
        // The distinction the whole package turns on. A US employer hiring into
        // India is a job whose country is US and whose employment country is IN,
        // and an answer scoped to India is the one that applies.
        ApplicationContext remote = Contexts.usRemoteFromIndia();
        assertThat(Scope.country("IN").appliesTo(remote)).isTrue();
        assertThat(Scope.country("US").appliesTo(remote)).isFalse();
    }

    @Test
    @DisplayName("a German scope does not apply to an Indian application, and vice versa")
    void countryScopesDoNotLeak() {
        assertThat(Scope.country("DE").appliesTo(Contexts.indiaOnsite())).isFalse();
        assertThat(Scope.country("IN").appliesTo(Contexts.germanyOnsite())).isFalse();
        assertThat(Scope.country("DE").appliesTo(Contexts.germanyOnsite())).isTrue();
    }

    @Test
    @DisplayName("a company scope does not reach another company")
    void companyScopesDoNotLeak() {
        assertThat(Scope.company("Camunda").appliesTo(Contexts.germanyOnsite())).isTrue();
        assertThat(Scope.company("Datadog").appliesTo(Contexts.germanyOnsite())).isFalse();
    }

    @Test
    @DisplayName("an application scope reaches exactly one posting")
    void applicationScopesDoNotLeak() {
        ApplicationContext one = Contexts.builder().postingId(1L).build();
        ApplicationContext two = Contexts.builder().postingId(2L).build();
        assertThat(Scope.application(1L).appliesTo(one)).isTrue();
        assertThat(Scope.application(1L).appliesTo(two)).isFalse();
    }

    @Test
    @DisplayName("work-mode and strategic-class scopes match their own field")
    void otherLevelsMatch() {
        ApplicationContext german = Contexts.germanyOnsite();
        assertThat(Scope.of(Scope.Level.WORK_MODE, WorkMode.ONSITE.name()).appliesTo(german))
                .isTrue();
        assertThat(Scope.of(Scope.Level.WORK_MODE, WorkMode.REMOTE_GLOBAL.name())
                .appliesTo(german)).isFalse();
        assertThat(Scope.of(Scope.Level.STRATEGIC_CLASS,
                StrategicClass.INTERNATIONAL_RELOCATION.name()).appliesTo(german)).isTrue();
    }

    @Test
    @DisplayName("an unknown context value never matches a specific scope")
    void unknownContextMatchesNothingSpecific() {
        // Falling back to "matches everything" when the country cannot be worked
        // out is exactly how a German answer would reach an unclassified job.
        ApplicationContext unstated = Contexts.remoteUnstated();
        assertThat(unstated.employmentCountryCode()).isNull();
        assertThat(Scope.country("IN").appliesTo(unstated)).isFalse();
        assertThat(Scope.country("DE").appliesTo(unstated)).isFalse();
        assertThat(Scope.global().appliesTo(unstated)).isTrue();
    }

    @Test
    @DisplayName("a scope with a level but no value is pending, and matches nothing")
    void pendingScopeIsInert() {
        // What the extra-answers migration writes when the old list never
        // recorded which country an answer was for.
        Scope pending = Scope.of(Scope.Level.COUNTRY, null);
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.appliesTo(Contexts.germanyOnsite())).isFalse();
        assertThat(pending.appliesTo(Contexts.indiaOnsite())).isFalse();
        assertThat(pending.describe()).contains("not yet chosen");
    }

    @Test
    @DisplayName("a scope value is one token, never a list")
    void refusesMultipleValues() {
        // A form with several hidden value pickers sharing one name submits
        // "nl,,onsite,india_home". Stored as a scope it matches nothing and looks
        // saved, which is the worst of both. Refused, so it reads as pending.
        Scope scope = Scope.of(Scope.Level.COUNTRY, "nl,,onsite,india_home");
        assertThat(scope.isPending()).isTrue();
        assertThat(scope.appliesTo(Contexts.germanyOnsite())).isFalse();
    }

    @Test
    @DisplayName("scope values are compared case-insensitively")
    void valuesAreNormalised() {
        assertThat(Scope.country("de")).isEqualTo(Scope.country("DE"));
        assertThat(Scope.company("Camunda").appliesTo(Contexts.germanyOnsite())).isTrue();
    }
}
