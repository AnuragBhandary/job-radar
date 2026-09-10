package com.anuragbhandary.jobradar.chat;

import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.llm.ChatTurn;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.pipeline.PipelineService;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.prep.TechVocabulary;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the assistant is allowed to do.
 *
 * <p>The list is the security boundary, and it is short on purpose. Reading is
 * unrestricted - it can see every posting, the board and the profile - and the
 * only writes are bookmarking a job and moving a card between columns. Both are
 * trivially reversible by hand.
 *
 * <p><strong>Submitting is not here and will not be.</strong> Most boards accept
 * one application per posting forever, so a model misreading one field costs that
 * company permanently. Preparing is absent for a duller reason: it opens a
 * browser and takes the better part of a minute, which does not belong inside a
 * request someone is watching a cursor blink through. The assistant points at the
 * button instead.
 */
@Component
public class ChatTools {

    private static final Logger log = LoggerFactory.getLogger(ChatTools.class);

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final ApplicationAttemptRepository attempts;
    private final PipelineService pipeline;
    private final MatchScorer scorer;
    private final ResumeModel resume;
    private final ApplicantProfile profile;
    private final ObjectMapper json;

    public ChatTools(PostingRepository postings, BoardTokenRepository boards,
            ApplicationAttemptRepository attempts, PipelineService pipeline,
            MatchScorer scorer, ResumeModel resume, ApplicantProfile profile,
            ObjectMapper json) {
        this.postings = postings;
        this.boards = boards;
        this.attempts = attempts;
        this.pipeline = pipeline;
        this.scorer = scorer;
        this.resume = resume;
        this.profile = profile;
        this.json = json;
    }

    public List<ChatTurn.Tool> definitions() {
        return List.of(
                new ChatTurn.Tool("search_jobs",
                        "Search screened candidate postings. Returns id, company, role, "
                                + "country, match score and the score's headline reason, "
                                + "best match first. Use this for any question about which "
                                + "jobs to apply to.",
                        schema(Map.of(
                                "query", string("Words to match in the title or description, "
                                        + "e.g. 'kafka' or 'backend'. Optional."),
                                "country", string("One of INDIA, GERMANY, IRELAND, "
                                        + "NETHERLANDS, REMOTE. Optional."),
                                "minScore", integer("Only postings scoring at least this. "
                                        + "Optional."),
                                "limit", integer("How many to return, default 10, max 30.")))),

                new ChatTurn.Tool("get_posting",
                        "Everything about one posting: the full description, the score "
                                + "broken down by factor, and which technologies it names "
                                + "that the resume does not cover.",
                        schema(Map.of("id", integer("The posting id.")), "id")),

                new ChatTurn.Tool("board_summary",
                        "The application pipeline: how many jobs sit in each stage, and "
                                + "the entries in each, with their interest ids.",
                        schema(Map.of())),

                new ChatTurn.Tool("profile_summary",
                        "The applicant's resume, skills, work authorisation and salary "
                                + "bands. Call this before giving advice that depends on "
                                + "what he can claim.",
                        schema(Map.of())),

                new ChatTurn.Tool("save_job",
                        "Bookmark a posting onto the board at the Saved stage. Reversible.",
                        schema(Map.of("postingId", integer("The posting id.")), "postingId")),

                new ChatTurn.Tool("move_job",
                        "Move a board entry to a different stage. Stages: SAVED, PREPARED, "
                                + "APPLIED, SCREENING, INTERVIEW, OFFER, REJECTED, DROPPED.",
                        schema(Map.of(
                                "interestId", integer("The board entry id, from board_summary."),
                                "stage", string("The stage to move it to.")),
                                "interestId", "stage")));
    }

    /**
     * Runs one tool and returns its result as JSON for the model to read.
     *
     * <p>Every failure comes back as an {@code error} field rather than an
     * exception. A model that asked for a posting that does not exist should be
     * told so and allowed to recover, not have the conversation end.
     */
    public String run(String name, String argumentsJson) {
        try {
            JsonNode args = json.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
            return switch (name) {
                case "search_jobs" -> searchJobs(args);
                case "get_posting" -> getPosting(args);
                case "board_summary" -> boardSummary();
                case "profile_summary" -> profileSummary();
                case "save_job" -> saveJob(args);
                case "move_job" -> moveJob(args);
                default -> error("no such tool: " + name);
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", name, e.getMessage());
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private String searchJobs(JsonNode args) throws Exception {
        String query = args.path("query").asText("").toLowerCase(java.util.Locale.ROOT);
        String country = args.path("country").asText("");
        int minScore = args.path("minScore").asInt(0);
        int limit = Math.min(30, Math.max(1, args.path("limit").asInt(10)));

        List<Map<String, Object>> hits = postings.findRecommended().stream()
                .filter(posting -> country.isBlank()
                        || country.equalsIgnoreCase(String.valueOf(posting.getCountry())))
                .filter(posting -> query.isBlank()
                        || (posting.getTitle() + " " + nullSafe(posting.getDescriptionText()))
                                .toLowerCase(java.util.Locale.ROOT).contains(query))
                .map(posting -> Map.entry(posting, scorer.score(posting)))
                .filter(entry -> entry.getValue().score() >= minScore)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<Posting, MatchScore> e) -> e.getValue().score()).reversed())
                .limit(limit)
                .map(entry -> {
                    Posting posting = entry.getKey();
                    return Map.<String, Object>of(
                            "id", posting.getId(),
                            "company", companyOf(posting),
                            "role", posting.getTitle(),
                            "country", String.valueOf(posting.getCountry()),
                            "location", nullSafe(posting.getLocation()),
                            "posted", String.valueOf(posting.getPostedDate()),
                            "score", entry.getValue().score(),
                            "why", entry.getValue().headline());
                })
                .toList();

        return json.writeValueAsString(Map.of("count", hits.size(), "jobs", hits));
    }

    private String getPosting(JsonNode args) throws Exception {
        long id = args.path("id").asLong();
        Posting posting = postings.findById(id).orElse(null);
        if (posting == null) {
            return error("no posting with id " + id);
        }
        MatchScore score = scorer.score(posting);

        Set<String> wanted = TechVocabulary.found(
                posting.getTitle() + "\n" + nullSafe(posting.getDescriptionText()));
        Set<String> known = TechVocabulary.found(resumeText());
        List<String> gaps = wanted.stream().filter(term -> !known.contains(term)).toList();

        // A LinkedHashMap, not Map.of: that caps at ten pairs and this has twelve.
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", posting.getId());
        out.put("company", companyOf(posting));
        out.put("role", posting.getTitle());
        out.put("country", String.valueOf(posting.getCountry()));
        out.put("location", nullSafe(posting.getLocation()));
        out.put("statedPay", posting.getSalaryText() == null
                ? "not stated" : posting.getSalaryText());
        out.put("sponsorship", posting.getSponsorshipSignal() == null
                ? "not stated" : posting.getSponsorshipSignal());
        out.put("minYears", posting.getMinYears() == null ? "not screened"
                : posting.getMinYears() < 0 ? "none stated" : posting.getMinYears());
        out.put("score", Map.of(
                "total", score.score(),
                "band", score.band().label(),
                "factors", score.factors().stream().map(factor -> Map.of(
                        "name", factor.label(), "points", factor.points(),
                        "max", factor.max(), "detail", factor.detail())).toList()));
        out.put("technologiesYouLack", gaps);
        out.put("description", truncate(nullSafe(posting.getDescriptionText()), 6000));
        return json.writeValueAsString(out);
    }

    private String boardSummary() throws Exception {
        List<Map<String, Object>> columns = pipeline.board().stream()
                .filter(column -> column.size() > 0)
                .map(column -> Map.<String, Object>of(
                        "stage", column.stage().name(),
                        "count", column.size(),
                        "entries", column.entries().stream().map(entry -> Map.of(
                                "interestId", entry.interest().getId(),
                                "company", entry.interest().getCompany(),
                                "role", entry.interest().getRole(),
                                "score", entry.score() == null ? "unknown"
                                        : entry.score().score(),
                                "notes", nullSafe(entry.interest().getNotes()))).toList()))
                .toList();

        return json.writeValueAsString(Map.of(
                "columns", columns,
                "attemptsPrepared", attempts.count()));
    }

    private String profileSummary() throws Exception {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.putAll(Map.of(
                "name", profile.name().display(),
                "basedIn", profile.address().city() + ", " + profile.address().country(),
                "authorisedIn", profile.workAuthorisation().authorisedIn().stream()
                        .map(Enum::name).toList(),
                "needsSponsorshipElsewhere", true,
                "skills", resume.skills().stream().map(group ->
                        group.group() + ": " + String.join(", ", group.items())).toList(),
                "experience", resume.experience() == null ? List.of()
                        : resume.experience().stream().map(job ->
                                job.title() + " at " + job.company() + " (" + job.period()
                                        + ", " + nullSafe(job.note()) + ")").toList(),
                "projects", resume.projects().stream().map(project ->
                        project.name() + " [" + project.stack() + "]").toList(),
                "education", resume.education() == null ? List.of()
                        : resume.education().stream().map(degree ->
                                degree.degree() + ", " + degree.institution()).toList(),
                "salaryBands", profile.compensation().bands() == null ? Map.of()
                        : profile.compensation().bands().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        entry -> entry.getKey().name(),
                                        entry -> entry.getValue().textAnswer()))));
        return json.writeValueAsString(summary);
    }

    private String saveJob(JsonNode args) throws Exception {
        long postingId = args.path("postingId").asLong();
        var interest = pipeline.save(postingId, PipelineStage.SAVED);
        return json.writeValueAsString(Map.of(
                "saved", true, "interestId", interest.getId(),
                "company", interest.getCompany(), "role", interest.getRole()));
    }

    private String moveJob(JsonNode args) throws Exception {
        long interestId = args.path("interestId").asLong();
        String stage = args.path("stage").asText("");
        PipelineStage target;
        try {
            target = PipelineStage.valueOf(stage.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return error("not a stage: " + stage);
        }
        var interest = pipeline.move(interestId, target);
        return json.writeValueAsString(Map.of(
                "moved", true, "interestId", interest.getId(),
                "stage", interest.getStage().name()));
    }

    // ------------------------------------------------------------------

    private String resumeText() {
        StringBuilder text = new StringBuilder();
        resume.skills().forEach(group -> text.append(String.join(" ", group.items())).append(' '));
        resume.allTags().forEach(tag -> text.append(tag).append(' '));
        resume.projects().forEach(project -> text.append(project.stack()).append(' '));
        return text.toString();
    }

    private String companyOf(Posting posting) {
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    private String error(String message) {
        return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
    }

    private static Map<String, Object> schema(Map<String, Object> properties,
            String... required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of(required));
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Country is referenced only through its name; kept for the schema docs. */
    @SuppressWarnings("unused")
    private static final Class<Country> COUNTRY = Country.class;
}
