package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@link OracleTenants#plan}: employer names on Oracle boards, and test or
 * development copies switched off. Run before every fetch and after discovery,
 * so a newly discovered site is named before it reaches a review file.
 */
@Component
public class OracleBoardTidier {

    private final BoardTokenRepository boards;

    public OracleBoardTidier(BoardTokenRepository boards) {
        this.boards = boards;
    }

    /** A one-line summary, or null when nothing changed. */
    @Transactional
    public String tidy() {
        OracleTenants.Plan plan = OracleTenants.plan(boards.findAll(),
                com.anuragbhandary.jobradar.config.BoardTokenSeeder.oracleLabels());
        for (Map.Entry<BoardToken, String> e : plan.relabel().entrySet()) {
            e.getKey().setLabel(e.getValue());
        }
        for (Map.Entry<BoardToken, String> e : plan.retire().entrySet()) {
            e.getKey().setActive(false);
            e.getKey().setLastError("retired: " + e.getValue());
        }
        boards.saveAll(plan.relabel().keySet());
        boards.saveAll(plan.retire().keySet());
        if (plan.relabel().isEmpty() && plan.retire().isEmpty()) {
            return null;
        }
        return "Oracle boards: %d renamed to their employer, %d test or development copies retired"
                .formatted(plan.relabel().size(), plan.retire().size());
    }
}
