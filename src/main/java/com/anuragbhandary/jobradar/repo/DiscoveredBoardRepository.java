package com.anuragbhandary.jobradar.repo;

import com.anuragbhandary.jobradar.domain.DiscoveredBoard;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscoveredBoardRepository extends JpaRepository<DiscoveredBoard, Long> {

    Optional<DiscoveredBoard> findBySourceAndToken(Source source, String token);

    List<DiscoveredBoard> findBySource(Source source);
}
