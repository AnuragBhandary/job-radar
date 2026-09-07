package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The assistant behind the review page.
 *
 * <p>Three jobs, all grounded in the same material the resume is built from:
 * draft an answer to an application question, rewrite the cover letter, and
 * answer a question about the posting.
 *
 * <p>The first two produce text that will be sent to an employer under the
 * applicant's name, so they run through the same rules as the cover letter:
 * facts only from the profile, dash punctuation rewritten, machine-sounding
 * phrasing rejected and regenerated once. The third is for the applicant to read
 * and is only cleaned, not policed - it is allowed to say "the posting does not
 * say", which is exactly what a filter tuned for outbound prose would reject.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final LlmClient llm;
    private final ResumeModel resume;
    private final ApplicantProfile profile;

    public AssistantService(LlmClient llm, ResumeModel resume, ApplicantProfile profile) {
        this.llm = llm;
        this.resume = resume;
        this.profile = profile;
    }

    public boolean isUsable() {
        return llm.isUsable();
    }

    /**
     * @param text     what to use, or null when nothing acceptable came back
     * @param rejected why the first attempt was thrown away, for the page to show.
     *                 Surfaced rather than hidden: "the first draft said
     *                 'passionate about' so I asked again" is the feature working,
     *                 and seeing it is how the phrase list earns trust.
     */
    public record Draft(String text, String rejected) {

        public boolean ok() {
            return text != null && !text.isBlank();
        }

        static Draft of(String text) {
            return new Draft(text, null);
        }

        static Draft none() {
            return new Draft(null, null);
        }
    }

    /**
     * Drafts an answer to one application question.
     *
     * <p>The instruction that matters is the last one: an answer it cannot support
     * from the material must come back as the single word NOTHING rather than as
     * a plausible sentence. A fabricated answer on an application is worse than a
     * blank one, and a blank one is what the tool already does well.
     */
    public Draft answerQuestion(Posting posting, String company, String question) {
        if (!llm.isUsable() || question == null || question.isBlank()) {
            return Draft.none();
        }

        String system = """
                You are drafting one answer to one question on a job application
                form, for the person described below. He will read it before it is
                sent, and he has to be able to defend every word of it in an
                interview.

                Rules:
                1. Use only what the material says. Do not invent projects,
                   technologies, employers, numbers or dates.
                2. Never state a number of years of experience.
                3. Answer the question that was asked. Two or three sentences
                   unless it obviously wants one word.
                4. If the material does not support an answer, reply with exactly:
                   NOTHING
                   Do not write a plausible answer instead. A blank on a form is
                   recoverable and a false claim is not.

                %s
                Output the answer only, with no preamble.
                """.formatted(HumanTone.styleRules());

        String user = "QUESTION\n" + question
                + "\n\nCONTEXT\nCompany: " + company
                + "\nRole: " + posting.getTitle()
                + "\n\n" + material();

        return policed(system, user, "answer");
    }

    /** Rewrites the letter, optionally against a steer typed on the page. */
    public Draft rewriteLetter(
            Posting posting, String company, String current, String instruction) {

        if (!llm.isUsable()) {
            return Draft.none();
        }

        String system = """
                You rewrite a job-application cover letter. Same facts, better
                writing. You may drop a sentence, you may not add a claim.

                Rules:
                1. Only facts from the material below. Nothing added.
                2. Never state a number of years of experience.
                3. No placeholders such as [Company].
                4. Pick at most two things he has built. Do not list everything,
                   that is what the resume is for.
                5. Exactly three paragraphs separated by a blank line, no greeting,
                   no sign-off, under 1800 characters and under 200 words. The
                   first paragraph must not start with "I". The second must say how
                   the work bears on this specific role.

                %s
                %s
                Output the letter only.
                """.formatted(HumanTone.styleRules(), HumanTone.letterExample());

        StringBuilder user = new StringBuilder();
        if (instruction != null && !instruction.isBlank()) {
            user.append("WHAT HE WANTS CHANGED\n").append(instruction.trim()).append("\n\n");
        }
        if (current != null && !current.isBlank()) {
            user.append("CURRENT LETTER\n").append(current).append("\n\n");
        }
        user.append("POSTING\nCompany: ").append(company)
                .append("\nRole: ").append(posting.getTitle())
                .append("\nDescription:\n").append(truncate(nullSafe(posting.getDescriptionText()), 4000))
                .append("\n\n").append(material());

        return policed(system, user.toString(), "letter");
    }

    /**
     * Answers a question about the posting, for the applicant to read.
     *
     * <p>Not policed for tone, because nothing here is sent anywhere. It is also
     * explicitly allowed to say it does not know, which is the answer that matters
     * most when the question is "does this mention sponsorship?".
     */
    public Draft ask(Posting posting, String company, String question) {
        if (!llm.isUsable() || question == null || question.isBlank()) {
            return Draft.none();
        }

        String system = """
                You answer questions about a job posting for the candidate who is
                about to apply to it. You have the posting text and his background.

                Two different kinds of question, and they are answered differently.

                A question of FACT about the role or company is answered from the
                posting. If the posting does not say, say that it does not say. Do
                not guess at a salary, a visa policy or a team size.

                A question of JUDGEMENT is answered by comparing the two documents
                you have been given. "What should I revise?", "am I underqualified?",
                "what will they push on?" are all answerable: name the specific
                things the posting asks for that his background does not show, and
                the specific things it does. Refusing these with "the posting does
                not say" is unhelpful and wrong, because the posting was never where
                the answer was.

                Be brief and concrete. Name technologies, not qualities. No dashes
                as punctuation, use commas or full stops.
                """;

        String user = "QUESTION\n" + question
                + "\n\nPOSTING\nCompany: " + company
                + "\nRole: " + posting.getTitle()
                + "\nLocation: " + nullSafe(posting.getLocation())
                + "\nStated pay: " + orNone(posting.getSalaryText())
                + "\nSponsorship language: " + orNone(posting.getSponsorshipSignal())
                + "\n\n" + truncate(nullSafe(posting.getDescriptionText()), 8000)
                + "\n\n" + material();

        return llm.complete(system, user)
                .map(HumanTone::removeDashes)
                .map(Draft::of)
                .orElse(Draft.none());
    }

    /** Generate, clean, judge, and give it exactly one more go with the reason. */
    private Draft policed(String system, String user, String what) {
        Optional<String> raw = llm.complete(system, user);
        if (raw.isEmpty()) {
            return Draft.none();
        }

        String text = HumanTone.removeDashes(strip(raw.get()));
        if (isRefusal(text)) {
            return Draft.none();
        }

        List<String> tells = new java.util.ArrayList<>(HumanTone.tells(text));
        if ("letter".equals(what)) {
            // Only the letter is written against the worked example, so only the
            // letter can borrow from it.
            tells.addAll(HumanTone.borrowedFromExample(text));
        }
        boolean dashes = HumanTone.hasDashes(text);
        // Structure is only judged on a letter. A one-line answer to "are you at
        // least 18?" is one paragraph starting with "I" and is exactly right.
        List<String> structure = "letter".equals(what)
                ? HumanTone.structureProblems(text) : List.of();

        if (tells.isEmpty() && !dashes && structure.isEmpty()) {
            return Draft.of(text);
        }

        log.info("First {} draft read as generated: {} {}", what,
                String.join(", ", tells), String.join("; ", structure));
        Optional<String> retry = llm.complete(
                system, user + "\n\n" + HumanTone.retryNote(tells, dashes, structure));
        if (retry.isEmpty()) {
            return Draft.none();
        }

        String second = HumanTone.removeDashes(strip(retry.get()));
        if (isRefusal(second)) {
            return Draft.none();
        }
        String rejected = "first draft used: " + String.join(", ", tells);
        // Returned even when the second draft still has tells. It is shown to a
        // human with the reason attached, and a slightly stiff draft he can edit
        // beats no draft at all - which is not true of the cover letter written
        // unattended, and is why that one falls back to a template instead.
        return new Draft(second, tells.isEmpty() ? null : rejected);
    }

    /** The model was told to say NOTHING when it cannot answer honestly. */
    private static boolean isRefusal(String text) {
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase("NOTHING") || trimmed.length() < 3;
    }

    /** Everything the model is allowed to draw on. */
    private String material() {
        StringBuilder out = new StringBuilder("HIS MATERIAL, the only facts you may use\n");
        out.append("Name: ").append(profile.name().display()).append('\n');
        out.append("Based in ").append(profile.address().city()).append(", ")
                .append(profile.address().country()).append('\n');

        if (resume.experience() != null) {
            out.append("Experience:\n");
            for (ResumeModel.Job job : resume.experience()) {
                out.append("- ").append(job.title()).append(" at ").append(job.company())
                        .append(" (").append(job.period()).append(", ")
                        .append(nullSafe(job.note())).append(")\n");
                job.bullets().forEach(b -> out.append("  * ").append(b.text()).append('\n'));
            }
        }
        out.append("Projects:\n");
        for (ResumeModel.Project project : resume.projects()) {
            out.append("- ").append(project.name()).append(" [").append(project.stack())
                    .append("]\n");
            project.bullets().forEach(b -> out.append("  * ").append(b.text()).append('\n'));
        }
        if (resume.education() != null) {
            out.append("Education:\n");
            resume.education().forEach(degree -> out.append("- ").append(degree.degree())
                    .append(", ").append(degree.institution())
                    .append(" (").append(degree.period()).append(")\n"));
        }
        return out.toString();
    }

    private static String strip(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        return cleaned;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }
}
