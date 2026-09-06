package com.anuragbhandary.jobradar.fetch;

/**
 * A board could not be read.
 *
 * <p>Checked on purpose. A failed board is a normal, expected outcome that the
 * caller must record in board health rather than allow to abort the run - one
 * dead token must not cost the other forty-five their fetch.
 */
public class FetchException extends Exception {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
