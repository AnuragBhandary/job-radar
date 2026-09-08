package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * The company a posting belongs to, in the form a person would write it.
 *
 * <p>A posting stores the board token it came from, which is a slug: "n26",
 * "razorpaysoftwareprivatelimited". The readable name lives on the board record
 * as its label. This lookup existed in two controllers and was missing from the
 * third, so the home page briefly listed "n26" as a company.
 *
 * <p>Cached per source and token. A list of forty rows would otherwise be forty
 * queries for a table of a hundred rows that changes when boards are seeded.
 */
@Service
public class CompanyNames {

    private final BoardTokenRepository boards;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CompanyNames(BoardTokenRepository boards) {
        this.boards = boards;
    }

    public String of(Posting posting) {
        if (posting == null || posting.getBoardToken() == null) {
            return "";
        }
        return cache.computeIfAbsent(posting.getSource() + "/" + posting.getBoardToken(),
                key -> boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                        .map(board -> board.getLabel() == null || board.getLabel().isBlank()
                                ? board.getToken() : board.getLabel())
                        .orElse(posting.getBoardToken()));
    }
}
