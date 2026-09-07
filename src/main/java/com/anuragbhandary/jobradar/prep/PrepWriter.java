package com.anuragbhandary.jobradar.prep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link PrepPack} as markdown.
 *
 * <p>Same reasoning as the digest: it survives the terminal scrolling, it reads on
 * a phone on the way to a call, and it is still there afterwards when the question
 * is what was said.
 *
 * <p>Gaps come before strengths. The strengths are already known - they are why
 * the application was made - and the half-hour before an interview is better spent
 * on the list of things there is currently no answer for.
 */
@Component
public class PrepWriter {

    public String toMarkdown(PrepPack pack) {
        StringBuilder out = new StringBuilder();

        out.append("# ").append(pack.company()).append(" — ").append(pack.role()).append("\n\n")
                .append("- ").append(pack.location()).append('\n')
                .append("- Experience asked for: ").append(pack.yearsWording()).append('\n')
                .append("- Stated pay: ").append(orNone(pack.salaryText())).append('\n')
                .append("- Sponsorship language: ").append(orNone(pack.sponsorshipSignal()))
                .append('\n')
                .append("- ").append(pack.url()).append("\n\n");

        if (pack.notes() != null) {
            out.append("## What they appear to do\n\n")
                    .append("_Written by a language model from the posting text; it is the one "
                            + "part of this file that can be wrong._\n\n")
                    .append(pack.notes()).append("\n\n");
        }

        out.append("## Revise these — named in the posting, absent from your resume\n\n");
        if (pack.gaps().isEmpty()) {
            out.append("_Nothing. Every technology this posting names is already on your "
                    + "resume._\n\n");
        } else {
            pack.gaps().forEach(gap -> out.append("- **").append(gap).append("**\n"));
            out.append("\nAn honest \"I have not used it, here is the nearest thing I have "
                    + "done\" beats a guess. They can tell.\n\n");
        }

        out.append("## You already have these\n\n");
        if (pack.covered().isEmpty()) {
            out.append("_No overlap found. Worth asking whether this posting is a fit._\n\n");
        } else {
            out.append(String.join(", ", pack.covered())).append("\n\n");
        }

        section(out, "Your own words, for when they ask", pack.talkingPoints(),
                "Verbatim from the resume they are holding.");
        section(out, "Expect to be asked", pack.questionsToExpect(), null);
        section(out, "Ask them", pack.questionsToAsk(), null);

        return out.toString();
    }

    public Path write(PrepPack pack, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("interview-prep.md");
        Files.writeString(file, toMarkdown(pack), StandardCharsets.UTF_8);
        return file;
    }

    private static void section(StringBuilder out, String title, List<String> items, String note) {
        if (items.isEmpty()) {
            return;
        }
        out.append("## ").append(title).append("\n\n");
        if (note != null) {
            out.append('_').append(note).append("_\n\n");
        }
        items.forEach(item -> out.append("- ").append(item).append('\n'));
        out.append('\n');
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "_not stated_" : value;
    }
}
