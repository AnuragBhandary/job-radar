package com.anuragbhandary.jobradar.knowledge.experience;

/**
 * How close the applicant's evidence is to what a question actually asked about.
 *
 * <p>Five levels, fixed and documented, because this decides what may be said to
 * an employer. A model choosing its own categories would be a model choosing how
 * strong a claim it is allowed to make, which is the one decision it must not
 * have.
 *
 * <h2>What each level licenses</h2>
 * The level does not decide the wording - {@link Positioning} does that - but it
 * decides the <em>strongest sentence the wording may contain</em>. Only
 * {@link #DIRECT} licenses "I have worked with X". Every other level requires the
 * answer to open by saying he has not.
 */
public enum ExperienceLevel {

    /**
     * He has used this, and there is evidence naming it.
     *
     * <p>The term appears in the resume's skills, in a project's stack or tags,
     * or in a bullet describing work he did. Java, Kafka, Spring Boot, FastAPI,
     * PostgreSQL, Redis, Docker. The answer may simply be yes.
     */
    DIRECT("worked with it", true),

    /**
     * He has not used this, and has used things it is built on or beside.
     *
     * <p>The interesting case, and the reason this class exists. Kubernetes is
     * not in the resume; Docker, containerised services, AWS and distributed
     * systems all are, and a person with those can talk about Kubernetes
     * honestly. The answer must open by saying he has not used it directly, and
     * may then connect what he has.
     */
    ADJACENT("related work, no direct use", false),

    /**
     * He has met the ideas rather than the tool.
     *
     * <p>Weaker than adjacent: the connection is to the concept the technology
     * implements rather than to a neighbouring technology. Terraform against AWS
     * and deployment work. Real, and worth less than adjacency.
     */
    CONCEPTUAL("the underlying ideas, not the tool", false),

    /**
     * No technical relationship, and a defensible engineering one.
     *
     * <p>A different corner of the field where the same engineering applies -
     * distributed systems, asynchronous work, correctness under load. Used only
     * where that relationship can be named, never as a way of saying something
     * about nothing.
     */
    TRANSFERABLE("a general engineering foundation", false),

    /**
     * Nothing connects.
     *
     * <p>Still answerable for an experience question - "not yet, and I pick
     * things up quickly" is a true and reasonable answer - but never for a
     * factual or legal one, and never with anything invented to fill the gap.
     */
    NONE("no relevant evidence", false);

    private final String summary;
    private final boolean claimable;

    ExperienceLevel(String summary, boolean claimable) {
        this.summary = summary;
        this.claimable = claimable;
    }

    /** A few words for a screen. */
    public String summary() {
        return summary;
    }

    /**
     * Whether "I have worked with this" may appear in the answer.
     *
     * <p>True for exactly one level. Everything else has to say he has not, and
     * {@link ClaimValidator} enforces it against the generated prose rather than
     * trusting the prompt.
     */
    public boolean allowsDirectClaim() {
        return claimable;
    }

    /** True when the answer has to open by saying he has not used the thing. */
    public boolean requiresDisclaimer() {
        return !claimable;
    }
}
