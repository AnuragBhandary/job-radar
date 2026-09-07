package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplyProperties;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.prep.PrepPack;
import com.anuragbhandary.jobradar.prep.PrepService;
import com.anuragbhandary.jobradar.prep.PrepWriter;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code prep --posting-id=123} - what to read before the interview.
 *
 * <p>Runs against any posting, applied to or not. It costs nothing and needs no
 * browser, so there is no reason to gate it on having applied - reading the gap
 * list before applying is a legitimate way to decide whether to.
 */
@Component
public class PrepCommand {

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final PrepService prep;
    private final PrepWriter writer;
    private final ApplyProperties config;

    public PrepCommand(PostingRepository postings, BoardTokenRepository boards,
            PrepService prep, PrepWriter writer, ApplyProperties config) {
        this.postings = postings;
        this.boards = boards;
        this.prep = prep;
        this.writer = writer;
        this.config = config;
    }

    public void run(Map<String, String> options) {
        String id = options.get("posting-id");
        if (id == null) {
            System.out.println("Usage: prep --posting-id=123 [--print]");
            return;
        }
        Optional<Posting> found = postings.findById(Long.valueOf(id));
        if (found.isEmpty()) {
            System.out.println("No posting with id " + id);
            return;
        }
        Posting posting = found.get();

        String company = boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());

        PrepPack pack = prep.build(posting, company);

        if ("true".equals(options.get("print"))) {
            System.out.println();
            System.out.println(writer.toMarkdown(pack));
            return;
        }

        Path directory = Path.of(expand(config.outputDir()))
                .resolve("prep-" + slug(company) + "-" + posting.getId());
        try {
            Path file = writer.write(pack, directory);
            System.out.printf("%n%s — %s%n", company, posting.getTitle());
            System.out.printf("  %d technology(ies) named, %d already on your resume, "
                    + "%d to revise%n",
                    pack.covered().size() + pack.gaps().size(),
                    pack.covered().size(), pack.gaps().size());
            if (!pack.gaps().isEmpty()) {
                System.out.println("  revise: " + String.join(", ", pack.gaps()));
            }
            System.out.println("  " + file);
        } catch (IOException e) {
            System.out.println("Could not write the pack: " + e.getMessage());
        }
    }

    private static String expand(String path) {
        return path != null && path.startsWith("~")
                ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private static String slug(String value) {
        String cleaned = value == null ? "x" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return cleaned.isEmpty() ? "x" : cleaned.substring(0, Math.min(30, cleaned.length()));
    }
}
