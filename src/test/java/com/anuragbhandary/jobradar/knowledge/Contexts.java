package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import java.util.List;
import java.util.Set;

/** Application contexts for the cases the resolver has to get right. */
public final class Contexts {

    private Contexts() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Bengaluru, onsite. He needs no permit. */
    public static ApplicationContext indiaOnsite() {
        return builder().company("PhonePe").country("IN").employer("IN")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INDIA_OTHER).build();
    }

    /** Berlin, onsite. He would have to move and would need a permit. */
    public static ApplicationContext germanyOnsite() {
        return builder().company("Camunda").country("DE").employer("DE")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INTERNATIONAL_RELOCATION).build();
    }

    /** New York, onsite. Excluded by strategy, still a real job. */
    public static ApplicationContext usOnsite() {
        return builder().company("Datadog").country("US").employer("US")
                .mode(WorkMode.ONSITE).lane(StrategicClass.INTERNATIONAL_RELOCATION).build();
    }

    /**
     * A US employer whose posting says a remote employee may be in India.
     *
     * <p>The case the whole knowledge package is shaped around: the job's country
     * is the United States and the <em>employment</em> country is India.
     */
    public static ApplicationContext usRemoteFromIndia() {
        return builder().company("Supabase").country("US").employer("US")
                .mode(WorkMode.REMOTE_GLOBAL).eligibleFrom("IN")
                .lane(StrategicClass.INTERNATIONAL_REMOTE).build();
    }

    /** "Remote - United States only". He cannot be in the US. */
    public static ApplicationContext usOnlyRemote() {
        return builder().company("Vercel").country("US").employer("US")
                .mode(WorkMode.REMOTE_COUNTRY_LOCKED).eligibleFrom("US")
                .lane(StrategicClass.INTERNATIONAL_RELOCATION).build();
    }

    /** A Canadian employer hiring into India. */
    public static ApplicationContext canadaRemoteFromIndia() {
        return builder().company("Shopify").country("CA").employer("CA")
                .mode(WorkMode.REMOTE_GLOBAL).eligibleFrom("IN")
                .lane(StrategicClass.INTERNATIONAL_REMOTE).build();
    }

    /** A bare "Remote" that never said from where. */
    public static ApplicationContext remoteUnstated() {
        return builder().company("PostHog").country(null).employer(null)
                .mode(WorkMode.REMOTE_UNSPECIFIED)
                .lane(StrategicClass.INTERNATIONAL_REMOTE).build();
    }

    public static final class Builder {
        private Long postingId = 1L;
        private String company = "Acme";
        private String country;
        private String employer;
        private WorkMode mode = WorkMode.ONSITE;
        private List<String> eligibleFrom = List.of();
        private StrategicClass lane;
        private Set<String> authorised = Set.of("IN");
        private String resumePath;
        private String coverLetter;

        public Builder postingId(Long id) {
            this.postingId = id;
            return this;
        }

        public Builder company(String company) {
            this.company = company;
            return this;
        }

        public Builder country(String code) {
            this.country = code;
            return this;
        }

        public Builder employer(String code) {
            this.employer = code;
            return this;
        }

        public Builder mode(WorkMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder eligibleFrom(String... codes) {
            this.eligibleFrom = List.of(codes);
            return this;
        }

        public Builder lane(StrategicClass lane) {
            this.lane = lane;
            return this;
        }

        /** Where he needs no permit. Defaults to India alone. */
        public Builder authorisedIn(String... codes) {
            this.authorised = Set.of(codes);
            return this;
        }

        public Builder documents(String resumePath, String coverLetter) {
            this.resumePath = resumePath;
            this.coverLetter = coverLetter;
            return this;
        }

        public ApplicationContext build() {
            return new ApplicationContext(postingId, company, "board", "GREENHOUSE",
                    "Backend Engineer", "backend", "somewhere", country, employer,
                    mode, eligibleFrom, lane, authorised, "IN", null,
                    resumePath, coverLetter);
        }
    }
}
