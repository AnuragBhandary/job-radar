package com.anuragbhandary.jobradar.pipeline;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pipeline board, and the one place a job's stage changes.
 *
 * <p>Three records describe an application and they answer different questions:
 * the spreadsheet is the hand-maintained history, {@code ApplicationAttempt} is
 * what the automation did, and {@link JobInterest} is where the job has got to.
 * This service keeps the third correct and mirrors what matters into the first.
 *
 * <p><strong>The database is the source of truth and the sheet is a mirror.</strong>
 * Writes go local first and are pushed to Sheets afterwards; a failed sheet write
 * is logged and does not roll anything back. The alternative - treating a Google
 * API as the system of record for a tool that has to work on a train - was tried
 * by having no local record at all, and it is why the status column sat unread
 * for months.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final JobInterestRepository interests;
    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final MatchScorer scorer;
    private final SheetsClient sheets;

    public PipelineService(JobInterestRepository interests, PostingRepository postings,
            BoardTokenRepository boards, MatchScorer scorer, SheetsClient sheets) {
        this.interests = interests;
        this.postings = postings;
        this.boards = boards;
        this.scorer = scorer;
        this.sheets = sheets;
    }

    /** A column of the board, with its entries already joined to their postings. */
    public record Column(PipelineStage stage, List<Entry> entries) {

        public int size() {
            return entries.size();
        }
    }

    /**
     * @param posting null for an application made before this tool existed
     * @param score   recomputed now, not the one stored at save time
     */
    public record Entry(JobInterest interest, Posting posting, MatchScore score) {

        public boolean isDue(LocalDate today) {
            return interest.isDue(today);
        }

        /** How far the score has moved since it was saved, or null. */
        public Integer drift() {
            if (score == null || interest.getScoreWhenSaved() == null) {
                return null;
            }
            return score.score() - interest.getScoreWhenSaved();
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Column> board() {
        Map<PipelineStage, List<Entry>> byStage = new LinkedHashMap<>();
        for (JobInterest interest : interests.findAll()) {
            byStage.computeIfAbsent(interest.getStage(), stage -> new ArrayList<>())
                    .add(toEntry(interest));
        }

        List<Column> columns = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            // A fresh list: getOrDefault returns the immutable List.of() for an
            // empty column, and sorting that throws.
            List<Entry> entries = new ArrayList<>(byStage.getOrDefault(stage, List.of()));
            // Highest score first inside a column, then most recently touched.
            // Sorting a column by date puts whatever was clicked last at the top,
            // which is the least useful ordering available.
            entries.sort((a, b) -> {
                int byScore = Integer.compare(
                        a.score() == null ? -1 : a.score().score(),
                        b.score() == null ? -1 : b.score().score());
                return byScore != 0 ? -byScore
                        : b.interest().getUpdatedAt().compareTo(a.interest().getUpdatedAt());
            });
            columns.add(new Column(stage, List.copyOf(entries)));
        }
        return columns;
    }

    @Transactional(readOnly = true)
    public List<Entry> dueReminders(LocalDate today) {
        return interests.findByRemindOnLessThanEqualOrderByRemindOnAsc(today).stream()
                .filter(interest -> !interest.getStage().isTerminal())
                .map(this::toEntry)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<JobInterest> forPosting(Long postingId) {
        return interests.findByPostingId(postingId);
    }

    private Entry toEntry(JobInterest interest) {
        Posting posting = interest.hasPosting()
                ? postings.findById(interest.getPostingId()).orElse(null) : null;
        return new Entry(interest, posting, posting == null ? null : scorer.score(posting));
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Bookmarks a posting. Idempotent: saving twice does not create two rows. */
    @Transactional
    public JobInterest save(Long postingId, PipelineStage stage) {
        Optional<JobInterest> existing = interests.findByPostingId(postingId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Posting posting = postings.findById(postingId).orElseThrow(
                () -> new IllegalArgumentException("No posting " + postingId));

        return interests.save(new JobInterest(
                postingId, companyOf(posting), posting.getTitle(), posting.getUrl(),
                stage, scorer.score(posting).score()));
    }

    /**
     * Moves a job to a new stage, and mirrors the move into the spreadsheet.
     *
     * <p>Moving to {@link PipelineStage#APPLIED} appends a row if there is not one
     * already; every later move updates that row's status. A sheet failure is
     * logged and swallowed - the move has happened locally, and throwing here
     * would report the whole action as failed when only the mirror is behind.
     */
    @Transactional
    public JobInterest move(Long interestId, PipelineStage stage) {
        JobInterest interest = interests.findById(interestId).orElseThrow(
                () -> new IllegalArgumentException("No interest " + interestId));
        interest.setStage(stage);
        // Stamped on the first move into a sent stage and never revised: moving
        // Applied to Screening a fortnight later must not reset the clock that
        // says how long this has been running.
        if (stage.isSent() && interest.getAppliedOn() == null) {
            interest.setAppliedOn(LocalDate.now());
        }
        mirrorToSheet(interest);
        return interests.save(interest);
    }

    @Transactional
    public JobInterest annotate(Long interestId, String notes, LocalDate remindOn) {
        JobInterest interest = interests.findById(interestId).orElseThrow(
                () -> new IllegalArgumentException("No interest " + interestId));
        interest.setNotes(notes);
        interest.setRemindOn(remindOn);
        return interests.save(interest);
    }

    private void mirrorToSheet(JobInterest interest) {
        if (!sheets.isConfigured() || interest.getStage() == PipelineStage.SAVED
                || interest.getStage() == PipelineStage.PREPARED) {
            // Nothing before APPLIED belongs in the tracker. It is the record of
            // applications sent, and filling it with bookmarks would destroy the
            // one question it answers.
            return;
        }
        try {
            if (interest.getTrackerRow() != null) {
                sheets.updateStatus(interest.getTrackerRow(), interest.getStage().label());
                return;
            }
            Posting posting = interest.hasPosting()
                    ? postings.findById(interest.getPostingId()).orElse(null) : null;
            if (posting == null) {
                log.debug("No posting behind interest {} - not appending to the tracker",
                        interest.getId());
                return;
            }
            interest.setTrackerRow(sheets.appendApplication(
                    com.anuragbhandary.jobradar.sheets.ApplicationRow
                            .from(posting, interest.getCompany(), LocalDate.now())
                            .withNotes("via the pipeline board")));
        } catch (IOException | RuntimeException e) {
            log.warn("Local move saved, tracker not updated: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Importing what is already in the spreadsheet
    // ------------------------------------------------------------------

    /**
     * Seeds the board from the tracker.
     *
     * <p>The spreadsheet holds applications made before any of this existed, and a
     * board that started empty would be a worse record than the sheet on its first
     * day. Rows are matched to postings where possible and imported as
     * free-standing entries where not.
     *
     * <p>Idempotent by tracker row, so running it twice does not duplicate
     * anything, and it never overwrites a stage a human has since moved.
     */
    @Transactional
    /**
     * @param imported  rows the board had never seen
     * @param refreshed rows it already had, with a gap now filled from the sheet
     */
    public record ImportResult(int imported, int refreshed) {

        public String describe() {
            if (imported == 0 && refreshed == 0) {
                return "Nothing to import. The board already matches the sheet.";
            }
            StringBuilder said = new StringBuilder();
            if (imported > 0) {
                said.append("Imported ").append(imported)
                        .append(imported == 1 ? " row" : " rows");
            }
            if (refreshed > 0) {
                said.append(said.isEmpty() ? "Filled in dates on " : ", and refreshed ")
                        .append(refreshed).append(refreshed == 1 ? " row" : " rows");
            }
            return said.append('.').toString();
        }
    }

    public ImportResult importFromTracker() throws IOException {
        if (!sheets.isConfigured()) {
            return new ImportResult(0, 0);
        }
        // Keyed rather than listed: a re-import updates the row it already has
        // instead of skipping it. Skipping made the import a one-shot, so a
        // column added later - the applied date, for instance - could never
        // reach the rows imported before it existed.
        java.util.Map<Integer, JobInterest> known = new java.util.HashMap<>();
        interests.findAll().stream()
                .filter(i -> i.getTrackerRow() != null)
                .forEach(i -> known.put(i.getTrackerRow(), i));

        int imported = 0;
        int refreshed = 0;
        for (ExistingApplication row : sheets.readExistingApplications()) {
            if (row.company() == null || row.company().isBlank()) {
                continue;
            }
            JobInterest existing = known.get(row.rowNumber());
            if (existing != null) {
                // Only fills gaps. The stage is not overwritten: a card moved on
                // the board is a decision the spreadsheet has not heard about yet.
                if (existing.getAppliedOn() == null && row.dateApplied() != null) {
                    existing.setAppliedOn(row.dateApplied());
                    interests.save(existing);
                    refreshed++;
                }
                continue;
            }
            Optional<Posting> posting = findPosting(row);
            if (posting.isPresent() && interests.existsByPostingId(posting.get().getId())) {
                continue;
            }

            JobInterest interest = new JobInterest(
                    posting.map(Posting::getId).orElse(null),
                    row.company().replaceAll("\\s+", " ").trim(),
                    row.role() == null || row.role().isBlank() ? "(role not recorded)"
                            : row.role().trim(),
                    posting.map(Posting::getUrl).orElse(row.link()),
                    PipelineStage.fromTrackerStatus(row.status()),
                    posting.map(p -> scorer.score(p).score()).orElse(null));
            interest.setTrackerRow(row.rowNumber());
            // The sheet's own Date Applied. Previously dropped on the floor, which
            // left every imported row looking like it was sent the day of the
            // import - so nothing could tell a fresh application from one that had
            // been silent for a month.
            interest.setAppliedOn(row.dateApplied());
            interests.save(interest);
            imported++;
        }
        log.info("Imported {} tracker row(s), refreshed {}", imported, refreshed);
        return new ImportResult(imported, refreshed);
    }

    /**
     * The posting a tracker row refers to, if this tool ever saw it.
     *
     * <p>Matched on company and title together, both normalised. Company alone
     * would attach an Amazon SDE row to whichever of 493 Amazon postings came
     * first, which is worse than leaving it unlinked - an unlinked entry is
     * honestly a bare record, and a wrongly linked one claims a score and a
     * description that belong to a different job.
     */
    private Optional<Posting> findPosting(ExistingApplication row) {
        if (row.link() != null && !row.link().isBlank()) {
            Optional<Posting> byUrl = postings.findAll().stream()
                    .filter(posting -> row.link().equals(posting.getUrl()))
                    .findFirst();
            if (byUrl.isPresent()) {
                return byUrl;
            }
        }
        String company = normalise(row.company());
        String role = normalise(row.role());
        if (role.isBlank()) {
            return Optional.empty();
        }
        return postings.findAll().stream()
                .filter(posting -> normalise(posting.getTitle()).equals(role))
                .filter(posting -> normalise(companyOf(posting)).contains(company)
                        || company.contains(normalise(companyOf(posting))))
                .findFirst();
    }

    private String companyOf(Posting posting) {
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    private static String normalise(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
