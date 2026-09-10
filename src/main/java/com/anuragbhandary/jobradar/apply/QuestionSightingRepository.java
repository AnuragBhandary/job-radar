package com.anuragbhandary.jobradar.apply;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QuestionSightingRepository extends JpaRepository<QuestionSighting, Long> {

    List<QuestionSighting> findByConceptId(String conceptId);

    List<QuestionSighting> findByState(FieldState state);

    List<QuestionSighting> findByAttemptId(Long attemptId);

    void deleteByAttemptId(Long attemptId);

    /**
     * Which concepts interrupt the most applications.
     *
     * <p>The question {@code AnswerBank} was built to answer, now asked of the
     * database rather than of a tab-separated column parsed at read time.
     * Ordered by how often a question actually stopped a form, then by how often
     * it merely appeared.
     */
    @Query("""
            select coalesce(s.conceptId, 'unrecognised') as concept,
                   count(s) as seen,
                   sum(case when s.required = true and s.state
                        = com.anuragbhandary.jobradar.apply.FieldState.AWAITING_ANSWER
                        then 1 else 0 end) as blocked
            from QuestionSighting s
            group by coalesce(s.conceptId, 'unrecognised')
            order by blocked desc, seen desc
            """)
    List<Object[]> interruptionCounts();

    /** Every distinct wording a board has used for one concept. */
    @Query("""
            select distinct s.rawLabel from QuestionSighting s
            where s.conceptId = :conceptId
            """)
    List<String> wordingsFor(String conceptId);
}
