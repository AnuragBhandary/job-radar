package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The one place a {@link RawPosting} becomes a {@link Posting}.
 *
 * <p>Every fetcher funnels through here so that hashing and normalisation are
 * defined once. If each fetcher hashed its own descriptions, the five
 * implementations would drift and change detection would behave differently per
 * board - which is the sort of bug that shows up as "the digest is a bit wrong"
 * six weeks later.
 */
@Component
public class PostingMapper {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Creates a new posting. {@code firstSeen} and {@code lastSeen} are both set
     * to now; the verdict stays UNSCREENED until the screening pass runs.
     */
    public Posting toNewPosting(Source source, String boardToken, RawPosting raw, Instant now) {
        Posting posting = new Posting(source, boardToken, raw.externalId(), raw.title());
        posting.setFirstSeen(now);
        applyFields(posting, raw, now);
        return posting;
    }

    /**
     * Updates an existing posting in place from a fresh fetch.
     *
     * <p>{@code firstSeen} is deliberately never touched - it is the answer to
     * "how long has this been open?", which is the one thing a re-fetch cannot
     * tell us.
     */
    public void applyFields(Posting posting, RawPosting raw, Instant now) {
        posting.setTitle(raw.title());
        posting.setLocation(raw.location());
        posting.setDescriptionText(raw.description());
        posting.setDescriptionHash(hash(raw.description()));
        posting.setUrl(raw.url());
        posting.setPostedDate(raw.postedDate());
        posting.setLastSeen(now);
    }

    /**
     * SHA-256 of the normalised description.
     *
     * <p>Normalisation is whitespace collapsing only. It is tempting to also
     * lowercase and strip punctuation to reduce noise, but a board editing
     * "2 years" to "2+ years" is exactly the change this tool must not miss, and
     * every normalisation step is a chance to erase one.
     *
     * @return a 64-character hex digest, or null for an absent description, so
     *         that "no description" and "empty description" stay distinguishable
     */
    public String hash(String description) {
        if (description == null) {
            return null;
        }
        String normalised = WHITESPACE.matcher(description).replaceAll(" ").trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing, nothing here works.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
