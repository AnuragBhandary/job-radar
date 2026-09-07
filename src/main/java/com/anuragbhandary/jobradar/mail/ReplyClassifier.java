package com.anuragbhandary.jobradar.mail;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reads what a company's email actually says.
 *
 * <p>Pure: subject and body in, {@link ReplyKind} out. No network, no model. A
 * model would classify these better and is not worth it here - the vocabulary of
 * rejection is small, fixed and remarkably consistent across companies, and the
 * cost of being wrong is a status written into the one record that cannot be
 * rebuilt.
 *
 * <p>Rules are ordered and the first match wins, hardest outcome first.
 *
 * <h2>The trap</h2>
 * Rejection and interview language overlap almost completely, because rejections
 * are written to sound like near-misses:
 *
 * <pre>
 *   "we would like to invite you to the next stage"      interview
 *   "we have decided not to invite you to the next stage" rejection
 *   "unfortunately we will not be moving forward"         rejection
 *   "we are moving forward with your application"         interview
 * </pre>
 *
 * Both contain "invite", "next stage" and "moving forward". The negation carries
 * the meaning, so rejection markers are checked first and are phrases rather than
 * words.
 */
@Component
public class ReplyClassifier {

    /**
     * Checked first. Every one is a phrase containing its own negation, because
     * the positive half of each is standard interview wording.
     */
    private static final List<String> REJECTION = List.of(
            "we regret to inform", "regret to inform you",
            "not to move forward", "not be moving forward", "not moving forward",
            "will not be progressing", "not be progressing", "not progressing",
            "decided not to", "unable to offer", "not to proceed",
            "not be proceeding", "not proceeding with your",
            "unsuccessful on this occasion", "were unsuccessful",
            "not selected", "not been selected", "not shortlisted",
            "pursue other candidates", "pursuing other candidates",
            "other candidates whose", "closer match",
            "keep your details on file", "keep your cv on file",
            "position has been filled", "role has been filled",
            "no longer under consideration", "not under consideration",
            "wish you the best in your search", "wish you all the best in your job");

    private static final List<String> OFFER = List.of(
            "offer of employment", "pleased to offer", "delighted to offer",
            "we would like to offer you", "your offer letter", "employment contract");

    /**
     * Language addressed to <em>this</em> applicant: someone is asking for time.
     *
     * <p>Split from {@link #INTERVIEW_DESCRIBED} and checked above acknowledgements,
     * because the two are distinguished by who the sentence is about, not by
     * vocabulary.
     */
    private static final List<String> INTERVIEW_DIRECT = List.of(
            "schedule an interview", "invite you to interview",
            "invite you for an interview", "like to interview",
            "book a time", "schedule a call", "set up a call",
            "screening call", "introductory call",
            "calendly.com", "moving forward with your application",
            "would like to speak with you", "speak with you about your application",
            "available for a chat", "pick a time", "your availability");

    /**
     * The same words used to describe a process rather than to invite anyone.
     *
     * <p>Checked <em>below</em> acknowledgements. "Thank you for applying. Our
     * process is a phone screen followed by a technical interview" is an automated
     * receipt that happens to contain the word "interview", and reading it as an
     * invitation marks the application as progressing on the day it was sent.
     */
    private static final List<String> INTERVIEW_DESCRIBED = List.of(
            "phone screen", "next stage of the process", "next round",
            "meet the team", "technical interview", "interview process");

    private static final List<String> ASSESSMENT = List.of(
            "online assessment", "coding challenge", "coding test",
            "take-home", "take home assignment", "technical assessment",
            "hackerrank", "codility", "codesignal", "karat",
            "complete the assessment", "complete this challenge");

    /**
     * Automated receipts. Checked last of the meaningful kinds, because several of
     * them also contain the word "interview" in a boilerplate description of the
     * process ahead.
     */
    private static final List<String> ACKNOWLEDGEMENT = List.of(
            "we have received your application", "thank you for applying",
            "thanks for applying", "your application has been received",
            "application received", "we've received your application",
            "thank you for your interest in", "successfully submitted",
            "your application is being reviewed", "reviewing your application");

    /**
     * Mail that is about jobs but not about an application of his.
     *
     * <p>Job alerts are the noise problem: they mention the company, the role and
     * the word "application", and there are dozens a week. Classified explicitly
     * rather than left to fall through, so they never reach the matcher.
     */
    private static final List<String> UNRELATED = List.of(
            "job alert", "jobs you may be interested", "new jobs matching",
            "recommended jobs", "unsubscribe from job", "newsletter",
            "we are hiring", "refer a friend", "job digest");

    public ReplyKind classify(String subject, String body) {
        String text = (nullSafe(subject) + "\n" + nullSafe(body)).toLowerCase(Locale.ROOT);

        // Job alerts first: they contain every other vocabulary here.
        if (matchesAny(text, UNRELATED)) {
            return ReplyKind.UNRELATED;
        }
        if (matchesAny(text, REJECTION)) {
            return ReplyKind.REJECTION;
        }
        if (matchesAny(text, OFFER)) {
            return ReplyKind.OFFER;
        }
        // Assessment vocabulary is specific enough not to appear in boilerplate -
        // no receipt says "your HackerRank test is ready" - so it stays above the
        // acknowledgement check.
        if (matchesAny(text, ASSESSMENT)) {
            return ReplyKind.ASSESSMENT;
        }
        if (matchesAny(text, INTERVIEW_DIRECT)) {
            return ReplyKind.INTERVIEW;
        }
        if (matchesAny(text, ACKNOWLEDGEMENT)) {
            return ReplyKind.ACKNOWLEDGEMENT;
        }
        // Only reached when nothing above matched, so there is no receipt wording
        // for this to be mistaken for.
        if (matchesAny(text, INTERVIEW_DESCRIBED)) {
            return ReplyKind.INTERVIEW;
        }
        return ReplyKind.UNRELATED;
    }

    private static boolean matchesAny(String text, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
