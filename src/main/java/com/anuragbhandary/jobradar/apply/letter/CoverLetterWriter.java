package com.anuragbhandary.jobradar.apply.letter;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the letter that goes in the text box, and only when there is one.
 *
 * <p>Called by {@link com.anuragbhandary.jobradar.apply.ApplyService} solely after
 * a {@link com.anuragbhandary.jobradar.apply.form.FieldKind#COVER_LETTER_TEXT}
 * field has been found on the page. A form offering a cover-letter <em>upload</em>
 * gets nothing, which is the instruction and is also right: an optional
 * attachment on an ATS is read by nobody, and the same paragraphs pasted into a
 * box are at least in front of the person screening.
 *
 * <h2>The output is checked before it is used</h2>
 * A model asked for a cover letter will, unprompted, write "with over 3 years of
 * experience" and "[Company Name]". The first is a false claim on an application
 * by someone whose work history may be verified; the
 * second is the visible tell of a mass-generated letter. Both are caught by
 * {@link #validate}, and a letter that fails validation is discarded in favour of
 * the template rather than repaired - a model that produced one invention has not
 * earned a second try.
 */
@Component
public class CoverLetterWriter {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterWriter.class);

    /** Most boards cap the box; this fits the smallest common one. */
    private static final int MAX_CHARS = 2000;

    /**
     * Phrases that mean the model invented something, or left a placeholder in.
     *
     * <p>"years of experience" is on the list without qualification. The honest
     * figure is one year, unpaid, at a company that no longer exists - a sentence
     * a model will not produce and the applicant should say in his own words if
     * he says it at all.
     */
    private static final List<String> FORBIDDEN = List.of(
            "years of experience", "years' experience", "years of professional",
            "[company", "[role", "[position", "[name", "{{", "insert ",
            "as an ai", "language model", "i am writing to express my strong interest");

    private final LlmClient llm;
    private final ApplicantProfile profile;

    public CoverLetterWriter(LlmClient llm, ApplicantProfile profile) {
        this.llm = llm;
        this.profile = profile;
    }

    /**
     * @param limit the box's own maximum length where the form states one
     * @return the letter, or empty if nothing acceptable could be produced. Empty
     *         is a valid outcome: an unanswered optional textarea beats a bad
     *         letter.
     */
    public Optional<String> write(
            Posting posting, String company, TailoredResume resume, int limit) {

        int cap = limit > 0 ? Math.min(limit, MAX_CHARS) : MAX_CHARS;

        if (llm.isUsable()) {
            String user = userPrompt(posting, company, resume);

            Optional<String> first = draft(systemPrompt(cap), user, cap);
            if (first.isPresent()) {
                return first;
            }

            // One retry, and only one. The retry is worth having because the
            // second attempt is told exactly which words were wrong, which is a
            // different request from the first. A third would be the same request
            // again, and a model that has ignored named feedback once will ignore
            // it twice.
            Optional<String> second = draft(systemPrompt(cap), user + "\n\n" + lastRejection, cap);
            if (second.isPresent()) {
                log.info("Second draft accepted");
                return second;
            }
            log.info("Both drafts rejected - using the template");
        }
        return Optional.of(clean(template(posting, company, resume))).filter(t -> !t.isBlank());
    }

    /** What was wrong with the previous draft, for the retry prompt. */
    private String lastRejection = "";

    /**
     * One attempt: generate, strip dash punctuation, then judge what is left.
     *
     * <p>The order matters. Dashes are rewritten before judging because they are a
     * formatting habit and fixing one changes nothing the sentence claims;
     * everything else is grounds for rejection, because the tells are whole
     * phrases and cutting them out leaves the sentence around them still shaped
     * wrong.
     */
    private Optional<String> draft(String system, String user, int cap) {
        Optional<String> raw = llm.complete(system, user);
        if (raw.isEmpty()) {
            lastRejection = "";
            return Optional.empty();
        }

        String text = clean(raw.get());

        if (!validate(text, cap)) {
            lastRejection = "Your previous draft was rejected for stating something "
                    + "that is not in the material given, or for leaving a placeholder "
                    + "in. Use only the facts above.";
            return Optional.empty();
        }

        List<String> tells = new java.util.ArrayList<>(HumanTone.tells(text));
        tells.addAll(HumanTone.borrowedFromExample(text));
        List<String> structure = HumanTone.structureProblems(text);
        boolean dashes = HumanTone.hasDashes(text);
        if (!tells.isEmpty() || dashes || !structure.isEmpty()) {
            log.info("Draft reads as generated{}{}{}",
                    tells.isEmpty() ? "" : " (" + String.join(", ", tells) + ")",
                    dashes ? " and used a dash" : "",
                    structure.isEmpty() ? "" : " [" + String.join("; ", structure) + "]");
            lastRejection = HumanTone.retryNote(tells, dashes, structure);
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private String systemPrompt(int cap) {
        return """
                You write short job-application cover letters for one specific person.

                Absolute rules, in order of importance:
                1. Use ONLY facts present in the APPLICANT MATERIAL below. You may
                   rephrase them. You may not add, extrapolate or round anything.
                2. Never state a number of years of experience. Never estimate one.
                3. Never use a placeholder such as [Company] or {{role}}. If you do
                   not have a fact, leave the sentence out.
                4. No flattery about the company. Say what he built and why it bears
                   on this posting.
                5. Pick AT MOST TWO things he has built. Do not list everything he
                   has done, that is what the resume is for. A letter that lists
                   six projects is a worse letter than one that explains one.
                6. One of those two must connect to something THIS posting actually
                   asks for, and you must say which.

                %s
                Format: exactly three paragraphs separated by a blank line. No
                greeting, no sign-off. Under %d characters and under 200 words.

                  Paragraph 1: the most relevant thing he has built, and what was
                                hard about it. Do not open with "I".
                  Paragraph 2: how that bears on this specific role.
                  Paragraph 3: one or two sentences, what he is looking for.

                %s
                Output the letter only.
                """.formatted(HumanTone.styleRules(), cap, HumanTone.letterExample());
    }

    private String userPrompt(Posting posting, String company, TailoredResume resume) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("POSTING\n")
                .append("Company: ").append(company).append('\n')
                .append("Role: ").append(posting.getTitle()).append('\n')
                .append("Location: ").append(nullSafe(posting.getLocation())).append('\n')
                .append("Description (truncated):\n")
                .append(truncate(nullSafe(posting.getDescriptionText()), 4000))
                .append("\n\nAPPLICANT MATERIAL - the only facts you may use\n")
                .append("Name: ").append(profile.name().display()).append('\n')
                .append("Summary: ").append(resume.summary().text()).append('\n');

        prompt.append("Experience:\n");
        for (ResumeModel.Job job : resume.experience()) {
            prompt.append("- ").append(job.title()).append(" at ").append(job.company())
                    .append(" (").append(job.period()).append(", ")
                    .append(nullSafe(job.note())).append(")\n");
            job.bullets().forEach(b -> prompt.append("  * ").append(b.text()).append('\n'));
        }
        prompt.append("Projects:\n");
        for (ResumeModel.Project project : resume.projects()) {
            prompt.append("- ").append(project.name())
                    .append(" [").append(project.stack()).append("]\n");
            project.bullets().forEach(b -> prompt.append("  * ").append(b.text()).append('\n'));
        }
        if (!resume.matchedTags().isEmpty()) {
            prompt.append("Overlap between this posting and his work: ")
                    .append(String.join(", ", resume.matchedTags())).append('\n');
        }
        return prompt.toString();
    }

    /**
     * The fallback, and the floor on quality.
     *
     * <p>Assembled entirely from sentences the applicant wrote, so it is always
     * safe to send. It reads like a template because it is one - which is still
     * better than an invented claim, and better than an empty box.
     */
    private String template(Posting posting, String company, TailoredResume resume) {
        StringBuilder letter = new StringBuilder();
        letter.append("I am applying for the ").append(posting.getTitle())
                .append(" role at ").append(company).append(".\n\n")
                .append(resume.summary().text()).append("\n\n");

        if (!resume.projects().isEmpty()) {
            ResumeModel.Project lead = resume.projects().getFirst();
            letter.append("Most relevant to this posting is ").append(lead.name())
                    .append(" (").append(lead.stack()).append("). ");
            if (!lead.bullets().isEmpty()) {
                letter.append(lead.bullets().getFirst().text());
                if (!lead.bullets().getFirst().text().endsWith(".")) {
                    letter.append('.');
                }
            }
            letter.append("\n\n");
        }

        if (!resume.matchedTags().isEmpty()) {
            letter.append("The overlap with what this role asks for is ")
                    .append(joinNaturally(resume.matchedTags()))
                    .append(". I would be glad to talk it through.");
        } else {
            letter.append("I would be glad to talk it through.");
        }
        return letter.toString();
    }

    /**
     * Rejects a letter rather than editing it.
     *
     * @return true if the text is safe to send
     */
    static boolean validate(String letter, int cap) {
        if (letter == null || letter.isBlank()) {
            return false;
        }
        if (letter.length() > cap) {
            log.info("Letter rejected: {} chars over the {} cap", letter.length() - cap, cap);
            return false;
        }
        String lower = letter.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN) {
            if (lower.contains(phrase)) {
                log.info("Letter rejected: contains '{}'", phrase);
                return false;
            }
        }
        // A model that ignored "no sign-off" has usually ignored the other rules
        // too, so this is treated as a signal rather than tidied away.
        return !lower.contains("sincerely,") && !lower.contains("yours faithfully");
    }

    /**
     * Strips the wrapping models add, then removes dash punctuation.
     *
     * <p>This used to replace an em dash with " - ", which is the same
     * punctuation mark spelled differently and reads exactly as machine-written.
     */
    static String clean(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        if (cleaned.length() > 1 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return HumanTone.removeDashes(cleaned);
    }

    private static String joinNaturally(List<String> items) {
        List<String> capped = items.size() > 4 ? items.subList(0, 4) : items;
        if (capped.size() == 1) {
            return capped.getFirst();
        }
        return String.join(", ", capped.subList(0, capped.size() - 1))
                + " and " + capped.getLast();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
