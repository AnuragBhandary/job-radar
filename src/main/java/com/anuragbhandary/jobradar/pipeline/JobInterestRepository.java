package com.anuragbhandary.jobradar.pipeline;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobInterestRepository extends JpaRepository<JobInterest, Long> {

    Optional<JobInterest> findByPostingId(Long postingId);

    List<JobInterest> findByStageOrderByUpdatedAtDesc(PipelineStage stage);

    List<JobInterest> findByStageInOrderByUpdatedAtDesc(List<PipelineStage> stages);

    /** Reminders due today or overdue. Oldest first, because those are the late ones. */
    List<JobInterest> findByRemindOnLessThanEqualOrderByRemindOnAsc(LocalDate today);

    boolean existsByPostingId(Long postingId);
}
