package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.LinkService;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code links --ids=a,b,c} - the employer's own link for each posting, and
 * whether it is still open.
 *
 * <p>Run on the day's picks before they are sent. Aggregator postings get their
 * employer page resolved (and the employer's board added when job-radar can
 * fetch it); every link is checked. A shortlisted posting's tracker link is
 * updated to the direct one.
 */
@Component
public class LinksCommand {

    private final PostingRepository postings;
    private final JobInterestRepository interests;
    private final LinkService links;

    public LinksCommand(PostingRepository postings, JobInterestRepository interests,
            LinkService links) {
        this.postings = postings;
        this.interests = interests;
        this.links = links;
    }

    public void run(Map<String, String> options) {
        if (options.containsKey("shortlist")) {
            pruneShortlist();
            return;
        }
        String ids = options.get("ids");
        if (ids == null || ids.isBlank()) {
            System.out.println("Usage: links --ids=<id>,<id>,...  |  links --shortlist");
            return;
        }
        for (String raw : ids.split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            Long id;
            try {
                id = Long.valueOf(raw.trim());
            } catch (NumberFormatException e) {
                System.out.println("Not a posting id: " + raw);
                continue;
            }
            Posting posting = postings.findById(id).orElse(null);
            if (posting == null) {
                System.out.println(id + "  no such posting");
                continue;
            }
            LinkService.Link link = links.forPosting(posting);
            if (link.url() != null && !link.check().dead()) {
                interests.findByPostingId(id).ifPresent(interest -> {
                    interest.setUrl(link.url());
                    interests.save(interest);
                });
            }
            System.out.printf("%-6d %-7s %s%n       %s%s%n", id, link.check().state(),
                    link.url() == null ? "-" : link.url(), link.check().reason(),
                    link.note() == null ? "" : " · " + link.note());
        }
    }

    /**
     * {@code links --shortlist}: checks every shortlisted posting and drops the
     * closed ones. Before 2026-10-20 that happened only inside {@code notify}, so
     * the backlog listed in a review could hold roles that had already closed
     * (Amazon Dublin and Tekion that day). An UNKNOWN answer leaves a row alone.
     */
    private void pruneShortlist() {
        int checked = 0;
        int dropped = 0;
        for (com.anuragbhandary.jobradar.pipeline.JobInterest row
                : interests.findByStageOrderByUpdatedAtDesc(com.anuragbhandary.jobradar.pipeline.PipelineStage.SAVED)) {
            if (row.getPostingId() == null) {
                continue;
            }
            Posting posting = postings.findById(row.getPostingId()).orElse(null);
            if (posting == null) {
                continue;
            }
            checked++;
            LinkService.Link link = links.forPosting(posting);
            if (!link.check().dead()) {
                continue;
            }
            dropped++;
            System.out.println("Dropped " + row.getPostingId() + " (" + row.getCompany() + " · "
                    + row.getRole() + "): " + link.check().reason());
            row.setStage(com.anuragbhandary.jobradar.pipeline.PipelineStage.DROPPED);
            row.addNote(java.time.LocalDate.now() + ": link dead, " + link.check().reason());
            interests.save(row);
        }
        System.out.printf("Checked %d shortlisted, dropped %d closed.%n", checked, dropped);
    }
}
