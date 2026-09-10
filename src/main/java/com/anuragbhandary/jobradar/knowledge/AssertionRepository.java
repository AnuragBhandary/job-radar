package com.anuragbhandary.jobradar.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssertionRepository extends JpaRepository<Assertion, Long> {

    /**
     * Every live assertion for a concept, whatever its scope.
     *
     * <p>Scope filtering happens in {@link KnowledgeResolver} against the
     * application context rather than in SQL. There are a handful of assertions
     * per concept, and {@link Scope#appliesTo} is where the safety rule lives -
     * putting half of it in a query string is how the two versions drift apart.
     */
    List<Assertion> findByConceptIdAndSupersededByIdIsNull(String conceptId);

    List<Assertion> findByConceptId(String conceptId);

    List<Assertion> findBySupersededByIdIsNull();

    List<Assertion> findByApprovalAndSupersededByIdIsNull(ApprovalState approval);

    /** Migrated with a scope nobody chose. The review queue. */
    List<Assertion> findByNeedsReviewTrueAndSupersededByIdIsNull();

    long countByConceptId(String conceptId);
}
