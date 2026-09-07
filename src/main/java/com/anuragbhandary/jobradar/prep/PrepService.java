package com.anuragbhandary.jobradar.prep;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds an interview prep pack from what is already known.
 *
 * <p>Almost entirely deterministic, and that is the point. The valuable half of
 * this is the <strong>gap list</strong> - technologies the posting names that
 * appear nowhere in his resume - and that is set arithmetic, not judgement. A
 * model asked "what should I revise?" produces a plausible list that is not
 * specific to either the posting or the applicant; subtracting one tag set from
 * another produces a short list that is specific to both.
 *
 * <p>The model, when configured, adds one thing: a paragraph on what the company
 * appears to do, from the description. It is clearly marked as such, because
 * unlike everything else here it can be wrong.
 */
@Service
public class PrepService {

    private final ResumeTailor tailor;
    private final ResumeModel resume;
    private final ApplicantProfile profile;
    private final LlmClient llm;

    public PrepService(ResumeTailor tailor, ResumeModel resume,
            ApplicantProfile profile, LlmClient llm) {
        this.tailor = tailor;
        this.resume = resume;
        this.profile = profile;
        this.llm = llm;
    }

    public PrepPack build(Posting posting, String company) {
        TailoredResume tailored = tailor.tailor(posting);

        String postingText = (posting.getTitle() + "\n" + nullSafe(posting.getDescriptionText()));
        Set<String> wanted = TechVocabulary.found(postingText);
        Set<String> known = TechVocabulary.found(resumeText());

        List<String> covered = wanted.stream().filter(known::contains).toList();
        // The half worth reading before the call.
        List<String> gaps = wanted.stream().filter(term -> !known.contains(term)).toList();

        return new PrepPack(
                company,
                posting.getTitle(),
                nullSafe(posting.getLocation()),
                posting.getUrl(),
                yearsWording(posting),
                posting.getSalaryText(),
                posting.getSponsorshipSignal(),
                covered,
                gaps,
                talkingPoints(tailored, wanted),
                questionsToExpect(covered, gaps),
                questionsToAsk(posting),
                companyNote(posting, company));
    }

    /**
     * His own bullets that mention something the posting asked for.
     *
     * <p>Verbatim, so the story told out loud is the one on the paper in front of
     * the interviewer. Paraphrasing a bullet in an interview and having it not
     * match the resume is a small thing that reads badly.
     */
    private List<String> talkingPoints(TailoredResume tailored, Set<String> wanted) {
        List<String> points = new ArrayList<>();
        tailored.experience().forEach(job -> job.bullets().forEach(bullet -> {
            if (mentionsAny(bullet.text(), wanted)) {
                points.add(job.company() + ": " + bullet.text());
            }
        }));
        tailored.projects().forEach(project -> project.bullets().forEach(bullet -> {
            if (mentionsAny(bullet.text(), wanted)) {
                points.add(project.name() + ": " + bullet.text());
            }
        }));
        return points;
    }

    /**
     * Questions that follow from what the posting asked for.
     *
     * <p>Derived from the technology list rather than invented: if a posting names
     * Kafka, the ordering-and-duplicates question is coming. The gap entries get
     * the harsher framing, because those are the ones with no rehearsed answer.
     */
    private static List<String> questionsToExpect(List<String> covered, List<String> gaps) {
        Set<String> questions = new LinkedHashSet<>();
        for (String term : covered) {
            String question = QUESTION_BY_TERM.get(term);
            if (question != null) {
                questions.add(question + "  (you have shipped this)");
            }
        }
        for (String term : gaps) {
            String question = QUESTION_BY_TERM.get(term);
            if (question != null) {
                questions.add(question + "  ⚠ NOT on your resume - prepare an honest answer");
            }
        }
        questions.add("Walk me through a system you designed end to end.");
        questions.add("Tell me about something you got wrong and how you found out.");
        return List.copyOf(questions);
    }

    private static final java.util.Map<String, String> QUESTION_BY_TERM =
            java.util.Map.ofEntries(
                    java.util.Map.entry("kafka",
                            "How do you guarantee ordering and handle duplicates in Kafka?"),
                    java.util.Map.entry("postgresql",
                            "How would you find and fix a slow query in PostgreSQL?"),
                    java.util.Map.entry("postgres",
                            "How would you find and fix a slow query in PostgreSQL?"),
                    java.util.Map.entry("redis",
                            "What do you use Redis for, and what happens when it is unavailable?"),
                    java.util.Map.entry("spring boot",
                            "How does Spring's dependency injection actually resolve a bean?"),
                    java.util.Map.entry("hibernate",
                            "What is the N+1 problem and how do you detect it?"),
                    java.util.Map.entry("kubernetes",
                            "What happens, step by step, when a pod fails its readiness probe?"),
                    java.util.Map.entry("docker",
                            "How do you keep an image small, and why does layer order matter?"),
                    java.util.Map.entry("microservices",
                            "How do two services stay consistent without a distributed transaction?"),
                    java.util.Map.entry("grpc",
                            "When would you choose gRPC over REST, and what do you give up?"),
                    java.util.Map.entry("graphql",
                            "How do you stop a GraphQL query from becoming an N+1 disaster?"),
                    java.util.Map.entry("aws",
                            "Which AWS services have you run in production, and what broke?"),
                    java.util.Map.entry("terraform",
                            "How do you manage Terraform state across a team?"),
                    java.util.Map.entry("observability",
                            "What do you instrument first in a new service?"),
                    java.util.Map.entry("event-driven",
                            "How do you replay events without double-processing?"),
                    java.util.Map.entry("distributed systems",
                            "What does 'exactly once' actually mean in practice?"),
                    java.util.Map.entry("rest",
                            "How do you version a public REST API?"),
                    java.util.Map.entry("tdd",
                            "What does a test that never fails cost you?"),
                    java.util.Map.entry("llm",
                            "How do you evaluate an LLM feature without a ground-truth set?"),
                    java.util.Map.entry("rag",
                            "Where does a RAG pipeline usually go wrong first?"));

    /**
     * What to ask them.
     *
     * <p>The first two are conditional on the posting and matter more than the
     * rest: an unanswered sponsorship question is a wasted process, and a salary
     * band unmentioned by a posting is one that has to be raised by him.
     */
    private List<String> questionsToAsk(Posting posting) {
        List<String> questions = new ArrayList<>();

        boolean needsSponsorship = !profile.workAuthorisation().isAuthorisedIn(posting.getCountry())
                && posting.getCountry() != Country.REMOTE;
        if (needsSponsorship) {
            questions.add(posting.getSponsorshipSignal() == null
                    ? "This posting says nothing about sponsorship. Have you sponsored a visa "
                            + "for this role before, and who handles it?"
                    : "The posting mentions sponsorship. What is the timeline from offer to "
                            + "start, and who pays the fees?");
        }
        if (posting.getSalaryText() == null || posting.getSalaryText().isBlank()) {
            questions.add("The posting states no band. What range is budgeted for this level?");
        }
        if (posting.getCountry() == Country.REMOTE) {
            questions.add("For a fully remote role worked from India - is this an employment "
                    + "contract, an EOR, or a contractor arrangement?");
        }

        questions.add("What does the first ninety days look like, concretely?");
        questions.add("Who would I be pairing with, and how does code get reviewed?");
        questions.add("What is the on-call rotation, and how often does it fire?");
        questions.add("What is the thing about this codebase that everyone complains about?");
        return questions;
    }

    /** One paragraph on what the company does. The only part a model writes. */
    private String companyNote(Posting posting, String company) {
        if (!llm.isUsable()) {
            return null;
        }
        return llm.complete("""
                You summarise what a company does, for someone about to interview there.
                Use only the job description given. Three sentences at most: what the
                product is, who pays for it, and what the engineering problem looks
                like. If the description does not say, write "the posting does not say"
                rather than guessing. No adjectives about the company being exciting.
                """,
                "Company: " + company + "\nRole: " + posting.getTitle()
                        + "\n\nDescription:\n" + truncate(nullSafe(posting.getDescriptionText()), 6000))
                .orElse(null);
    }

    private static String yearsWording(Posting posting) {
        Integer years = posting.getMinYears();
        if (years == null) {
            return "not screened";
        }
        return years < 0 ? "none stated" : years + "+ years";
    }

    private String resumeText() {
        StringBuilder text = new StringBuilder();
        resume.skills().forEach(group -> text.append(String.join(" ", group.items())).append(' '));
        resume.allTags().forEach(tag -> text.append(tag).append(' '));
        if (resume.experience() != null) {
            resume.experience().forEach(job ->
                    job.bullets().forEach(b -> text.append(b.text()).append(' ')));
        }
        resume.projects().forEach(project -> {
            text.append(project.stack()).append(' ');
            project.bullets().forEach(b -> text.append(b.text()).append(' '));
        });
        return text.toString();
    }

    private static boolean mentionsAny(String text, Set<String> terms) {
        String haystack = text.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(term -> TechVocabulary.containsWord(haystack, term));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
