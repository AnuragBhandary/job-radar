package com.anuragbhandary.jobradar.apply;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationAttemptRepository extends JpaRepository<ApplicationAttempt, Long> {

    List<ApplicationAttempt> findByStatusOrderByStartedAtDesc(AttemptStatus status);

    List<ApplicationAttempt> findByPostingIdOrderByStartedAtDesc(Long postingId);

    /**
     * The check that stops a second application to the same posting.
     *
     * <p>Only SUBMITTED counts. A previous PREPARED attempt is a draft and should
     * be re-preparable; a previous SUBMITTED one means the board has already had
     * the one application it will accept.
     */
    Optional<ApplicationAttempt> findFirstByPostingIdAndStatus(Long postingId, AttemptStatus status);

    List<ApplicationAttempt> findTop30ByOrderByStartedAtDesc();
}
