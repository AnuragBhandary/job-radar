package com.anuragbhandary.jobradar.apply.resume.analysis;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Whether a quote actually occurs in the posting it claims to come from.
 *
 * <p>The rule the whole requirements layer rests on: a model proposes a
 * requirement, and Java decides whether the posting said it. A requirement whose
 * quote cannot be found is a hallucination with a receipt, and it never reaches
 * the ledger.
 *
 * <h2>What "verbatim" tolerates</h2>
 * Typography and whitespace, and nothing else. Curly quotes become straight,
 * dashes become hyphens, runs of whitespace become one space, case is ignored -
 * because a job board's HTML-to-text conversion produces exactly those
 * differences, and a model copying the sentence faithfully should not fail on a
 * non-breaking space. A changed word, a reordered clause or two sentences
 * stitched together still fail.
 *
 * <p>Wrapping the model adds around a real span is also forgiven: surrounding
 * quote marks, a leading or trailing ellipsis, trailing punctuation. Removing
 * those only ever makes the quote shorter, so it cannot turn a false quote into a
 * true one.
 */
public final class QuoteGrounding {

    /** Shorter than this proves nothing: "a" and "to" occur in every posting. */
    public static final int MIN_QUOTE_CHARS = 3;

    private final String source;

    private QuoteGrounding(String source) {
        this.source = normalize(source);
    }

    /** Normalises the posting once, for checking many quotes against it. */
    public static QuoteGrounding of(String postingText) {
        return new QuoteGrounding(postingText);
    }

    public boolean contains(String quote) {
        String needle = normalize(trimWrapping(quote));
        return needle.length() >= MIN_QUOTE_CHARS && source.contains(needle);
    }

    public static boolean isGrounded(String postingText, String quote) {
        return of(postingText).contains(quote);
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String n = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replace(' ', ' ');
        return n.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    static String trimWrapping(String quote) {
        if (quote == null) {
            return "";
        }
        String q = Normalizer.normalize(quote, Normalizer.Form.NFKC).strip();
        String before;
        do {
            before = q;
            q = q.replaceAll("^[\"'“‘]+|[\"'”’]+$", "")
                    .replaceAll("^\\.\\.\\.\\s*|\\s*\\.\\.\\.$", "")
                    .replaceAll("[.,;:]+$", "")
                    .strip();
        } while (!q.equals(before));
        return q;
    }
}
