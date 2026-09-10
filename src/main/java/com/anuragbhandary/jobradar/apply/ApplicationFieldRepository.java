package com.anuragbhandary.jobradar.apply;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationFieldRepository extends JpaRepository<ApplicationField, Long> {

    List<ApplicationField> findByAttemptIdOrderByIdAsc(Long attemptId);

    List<ApplicationField> findByAttemptIdAndState(Long attemptId, FieldState state);

    List<ApplicationField> findByState(FieldState state);

    /** Known, and the page would not take it. The manual-intervention list. */
    List<ApplicationField> findByAutomationState(AutomationState automationState);

    void deleteByAttemptId(Long attemptId);

    long countByConceptIdAndState(String conceptId, FieldState state);
}
