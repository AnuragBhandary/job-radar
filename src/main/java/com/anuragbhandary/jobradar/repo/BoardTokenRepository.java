package com.anuragbhandary.jobradar.repo;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardTokenRepository extends JpaRepository<BoardToken, Long> {

    Optional<BoardToken> findBySourceAndToken(Source source, String token);

    List<BoardToken> findByActiveTrue();

    List<BoardToken> findBySourceAndActiveTrue(Source source);

    /** Boards whose last fetch failed - surfaced in the digest's board-health section. */
    List<BoardToken> findByLastErrorIsNotNull();
}
