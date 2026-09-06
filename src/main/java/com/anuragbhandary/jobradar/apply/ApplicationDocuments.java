package com.anuragbhandary.jobradar.apply;

import java.nio.file.Path;

/**
 * The two artefacts prepared for one application.
 *
 * @param resumePdf    the tailored resume, already rendered. Never null - an
 *                     application without a resume is not worth submitting.
 * @param coverLetter  the letter text, or {@code null} when the form has no box
 *                     to type one into. Null is the normal case and is not a
 *                     failure: writing a letter nobody asked for and attaching it
 *                     nowhere is wasted tokens.
 * @param tailoringNote what was changed for this posting, for the review file
 */
public record ApplicationDocuments(Path resumePdf, String coverLetter, String tailoringNote) {

    public boolean hasCoverLetter() {
        return coverLetter != null && !coverLetter.isBlank();
    }
}
