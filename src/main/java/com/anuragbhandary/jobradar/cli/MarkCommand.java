package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.PipelineService;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code mark <posting-id> <decision> [--note="..."]} - record a decision made in chat.
 *
 * <p>The handoff file is judged in a Claude session, and this is how the verdict
 * gets back into the tool. Any mark takes the posting out of every later handoff
 * file. {@code applied} and the stages after it are mirrored to the tracker sheet;
 * {@code skip} and {@code shortlist} never are, because the sheet is the record of
 * applications sent and a skipped posting was not one.
 */
@Component
public class MarkCommand {

    private static final Map<String, PipelineStage> DECISIONS = Map.of(
            "shortlist", PipelineStage.SAVED,
            "skip", PipelineStage.DROPPED,
            "applied", PipelineStage.APPLIED,
            "screening", PipelineStage.SCREENING,
            "interview", PipelineStage.INTERVIEW,
            "offer", PipelineStage.OFFER,
            "rejected", PipelineStage.REJECTED,
            "withdrawn", PipelineStage.DROPPED);

    private final PostingRepository postings;
    private final JobInterestRepository interests;
    private final PipelineService pipeline;

    public MarkCommand(PostingRepository postings, JobInterestRepository interests,
            PipelineService pipeline) {
        this.postings = postings;
        this.interests = interests;
        this.pipeline = pipeline;
    }

    public void run(ApplicationArguments args, Map<String, String> options) {
        List<String> positional = args.getNonOptionArgs();
        if (positional.size() < 3) {
            usage();
            return;
        }
        String decision = positional.get(2).toLowerCase(Locale.ROOT);
        PipelineStage stage = DECISIONS.get(decision);
        if (stage == null) {
            System.out.println("Unknown decision: " + decision);
            usage();
            return;
        }
        // Several ids at once, so a whole review is one command. Each is marked
        // on its own: one bad id does not stop the rest.
        for (String raw : positional.get(1).split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            try {
                markOne(Long.valueOf(raw.trim()), decision, stage, options.get("note"));
            } catch (NumberFormatException e) {
                System.out.println("Not a posting id: " + raw);
            }
        }
    }

    private void markOne(Long postingId, String decision, PipelineStage stage, String note) {
        if (postings.findById(postingId).isEmpty()) {
            System.out.println("No posting with id " + postingId);
            return;
        }

        Optional<JobInterest> existing = interests.findByPostingId(postingId);
        JobInterest interest;
        if (decision.equals("skip") || decision.equals("shortlist")) {
            if (existing.isPresent() && existing.get().getAppliedOn() != null) {
                // Skipping something already sent would rewrite history, and the
                // sheet row would stay "Applied" anyway.
                System.out.println("Posting " + postingId + " was applied to on "
                        + existing.get().getAppliedOn() + ". Use rejected or withdrawn.");
                return;
            }
            // Set directly rather than through PipelineService.move, which treats
            // every stage from APPLIED on as sent: that would stamp an applied date
            // on a skip and append it to the tracker as an application.
            interest = existing.orElseGet(() -> pipeline.save(postingId, PipelineStage.SAVED));
            interest.setStage(stage);
        } else {
            interest = pipeline.move(
                    existing.orElseGet(() -> pipeline.save(postingId, PipelineStage.SAVED)).getId(),
                    stage);
        }

        if (note != null && !note.isBlank()) {
            String before = interest.getNotes();
            interest.setNotes(before == null || before.isBlank() ? note : before + "\n" + note);
        }
        interest = interests.save(interest);

        System.out.println("Posting " + postingId + " (" + interest.getCompany() + " · "
                + interest.getRole() + ") marked " + interest.getStage().label()
                + (interest.getTrackerRow() != null ? ", tracker row " + interest.getTrackerRow() : "")
                + ".");
    }

    private static void usage() {
        System.out.println("Usage: mark <posting-id>[,<posting-id>...] <decision> [--note=\"...\"]");
        System.out.println("  decisions: " + String.join(", ",
                List.of("shortlist", "skip", "applied", "screening", "interview", "offer",
                        "rejected", "withdrawn")));
    }
}
