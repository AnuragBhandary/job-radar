package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.JobInterestRepository;
import com.anuragbhandary.jobradar.pipeline.PipelineStage;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code notify [--ids=a,b,c] [--title="..."] [--dry-run]} - post the shortlist to Discord.
 * {@code notify --quiet-day [--note="..."]} says a review found nothing.
 *
 * <p>Sends every shortlisted posting not yet applied to, one card each, to the
 * webhook in {@code job-radar.notify.discord-webhook} (secrets.yml). With
 * {@code --ids} it sends those postings in that order instead, which is how a
 * ranked review reaches the phone in the order it was ranked.
 *
 * <p>The note written by {@code mark --note} is the card's body, and its first
 * word sets the colour: "Apply" green, "Stretch" amber, anything else grey.
 */
@Component
public class NotifyCommand {

    /** Discord's cap on embeds in one message. */
    private static final int EMBEDS_PER_MESSAGE = 10;

    private static final int GREEN = 0x2E7D32;
    private static final int AMBER = 0xF9A825;
    private static final int GREY = 0x607D8B;

    private final JobInterestRepository interests;
    private final PostingRepository postings;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String webhook;
    private final com.anuragbhandary.jobradar.pipeline.LinkService links;

    public NotifyCommand(JobInterestRepository interests, PostingRepository postings,
            HttpClient http, ObjectMapper json,
            @Value("${job-radar.notify.discord-webhook:}") String webhook,
            com.anuragbhandary.jobradar.pipeline.LinkService links) {
        this.links = links;
        this.interests = interests;
        this.postings = postings;
        this.http = http;
        this.json = json;
        this.webhook = webhook;
    }

    public void run(Map<String, String> options) {
        boolean dryRun = options.containsKey("dry-run");
        if (!dryRun && (webhook == null || webhook.isBlank())) {
            System.out.println("No webhook: set job-radar.notify.discord-webhook in "
                    + "~/.config/job-radar/secrets.yml");
            return;
        }

        boolean check = !options.containsKey("no-check");

        if (options.containsKey("quiet-day")) {
            quietDay(options.get("note"), dryRun, check ? closedOnShortlist(Set.of(), dryRun) : List.of());
            return;
        }

        List<JobInterest> rows = select(options.get("ids"));
        // The rest of the shortlist too, every review: a role saved a week ago can
        // close before it is applied to, and the post says so before the new picks.
        List<String> closed = check ? closedOnShortlist(rows.stream()
                .map(JobInterest::getPostingId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()), dryRun) : List.of();
        if (rows.isEmpty()) {
            System.out.println("Nothing to send: the shortlist is empty.");
            return;
        }

        // Every link is checked before it is sent, and aggregator links are
        // swapped for the employer's own page. A dead one never reaches the phone.
        List<Map<String, Object>> cards = new ArrayList<>();
        for (JobInterest row : rows) {
            String warning = null;
            Posting posting = row.getPostingId() == null
                    ? null : postings.findById(row.getPostingId()).orElse(null);
            if (check && posting != null) {
                com.anuragbhandary.jobradar.pipeline.LinkService.Link link = links.forPosting(posting);
                if (link.check().dead()) {
                    System.out.println("Dropped " + row.getPostingId() + " (" + row.getCompany()
                            + "): " + link.check().reason());
                    row.setStage(PipelineStage.DROPPED);
                    row.addNote(LocalDate.now() + ": link dead, " + link.check().reason());
                    interests.save(row);
                    continue;
                }
                if (link.url() != null) {
                    row.setUrl(link.url());
                    interests.save(row);
                } else {
                    warning = "⚠️ Employer page not found automatically: search their careers site.";
                }
            }
            Map<String, Object> card = card(row);
            if (warning != null) {
                card.put("description", card.get("description") + "\n" + warning);
            }
            cards.add(card);
        }
        if (cards.isEmpty()) {
            System.out.println("Nothing left to send: every link was dead.");
            return;
        }

        String title = options.getOrDefault("title",
                "job-radar · " + LocalDate.now() + " · " + cards.size() + " to apply to");
        int sent = 0;
        for (int i = 0; i < cards.size(); i += EMBEDS_PER_MESSAGE) {
            Map<String, Object> message = new LinkedHashMap<>();
            if (i == 0) {
                message.put("content", clip("**" + title + "**" + closedLine(closed), 1900));
            }
            message.put("embeds", cards.subList(i, Math.min(i + EMBEDS_PER_MESSAGE, cards.size())));
            message.put("allowed_mentions", Map.of("parse", List.of()));
            if (dryRun) {
                System.out.println(toJson(message));
                continue;
            }
            if (!post(toJson(message))) {
                System.out.println("Stopped after " + sent + " of " + cards.size() + " cards.");
                return;
            }
            sent += Math.min(EMBEDS_PER_MESSAGE, cards.size() - i);
        }
        System.out.println(dryRun
                ? "Dry run: " + cards.size() + " card(s), nothing sent."
                : "Sent " + sent + " card(s) to Discord.");
    }

    /**
     * {@code notify --quiet-day [--note="..."]}: one plain message saying the review
     * ran and found nothing, so silence in the channel never has to be read as
     * "did it run?". Names what is still waiting on the shortlist.
     */
    private void quietDay(String note, boolean dryRun, List<String> closed) {
        List<JobInterest> waiting = interests.findByStageOrderByUpdatedAtDesc(PipelineStage.SAVED);
        StringBuilder text = new StringBuilder("**job-radar · " + LocalDate.now()
                + " · nothing new to apply to today**" + closedLine(closed));
        if (note != null && !note.isBlank()) {
            text.append('\n').append(clip(note.strip(), 1500));
        }
        if (waiting.isEmpty()) {
            text.append("\nShortlist: empty.");
        } else {
            text.append("\nStill on the shortlist: ");
            text.append(String.join(", ", waiting.stream()
                    .map(i -> companyAndRole(i.getCompany(), i.getRole()))
                    .map(cr -> cr[0] + " (" + cr[1] + ")")
                    .toList()));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", clip(text.toString(), 1900));
        message.put("allowed_mentions", Map.of("parse", List.of()));
        if (dryRun) {
            System.out.println(toJson(message));
            System.out.println("Dry run: quiet-day message, nothing sent.");
            return;
        }
        System.out.println(post(toJson(message))
                ? "Sent the quiet-day message to Discord." : "Quiet-day message not sent.");
    }

    /**
     * Checks every shortlisted posting not in {@code sending} and drops the ones
     * whose page is gone, as a dead pick is dropped. Returns "Company (role)" for
     * each. An UNKNOWN answer (Workday's 403s) leaves a row alone. A dry run only
     * reports.
     */
    private List<String> closedOnShortlist(Set<Long> sending, boolean dryRun) {
        List<String> closed = new ArrayList<>();
        for (JobInterest row : interests.findByStageOrderByUpdatedAtDesc(PipelineStage.SAVED)) {
            if (row.getPostingId() == null || sending.contains(row.getPostingId())) {
                continue;
            }
            Posting posting = postings.findById(row.getPostingId()).orElse(null);
            if (posting == null) {
                continue;
            }
            com.anuragbhandary.jobradar.pipeline.LinkService.Link link = links.forPosting(posting);
            if (!link.check().dead()) {
                continue;
            }
            String[] cr = companyAndRole(row.getCompany(), row.getRole());
            closed.add(cr[0] + " (" + cr[1] + ")");
            System.out.println((dryRun ? "Would drop " : "Dropped ") + row.getPostingId()
                    + " from the shortlist (" + row.getCompany() + "): " + link.check().reason());
            if (!dryRun) {
                row.setStage(PipelineStage.DROPPED);
                row.addNote(LocalDate.now() + ": link dead, " + link.check().reason());
                interests.save(row);
            }
        }
        return closed;
    }

    private static String closedLine(List<String> closed) {
        return closed.isEmpty() ? "" : "\n⚠️ Closed before you applied, taken off the shortlist: "
                + String.join(", ", closed);
    }

    private List<JobInterest> select(String ids) {
        if (ids == null || ids.isBlank()) {
            List<JobInterest> saved = new ArrayList<>(
                    interests.findByStageOrderByUpdatedAtDesc(PipelineStage.SAVED));
            java.util.Collections.reverse(saved);
            return saved;
        }
        List<JobInterest> picked = new ArrayList<>();
        for (String raw : ids.split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            Optional<JobInterest> row;
            try {
                row = interests.findByPostingId(Long.valueOf(raw.trim()));
            } catch (NumberFormatException e) {
                System.out.println("Not a posting id: " + raw);
                continue;
            }
            if (row.isEmpty()) {
                System.out.println("Posting " + raw.trim() + " is not on the shortlist; skipped.");
                continue;
            }
            picked.add(row.get());
        }
        return picked;
    }

    private Map<String, Object> card(JobInterest row) {
        Posting posting = row.getPostingId() == null
                ? null : postings.findById(row.getPostingId()).orElse(null);
        String[] companyRole = companyAndRole(row.getCompany(), row.getRole());
        String location = posting == null || posting.getLocation() == null
                ? "" : posting.getLocation();
        String url = row.getUrl() != null ? row.getUrl()
                : posting == null ? null : posting.getUrl();
        String note = latestNote(row.getNotes());

        StringBuilder body = new StringBuilder();
        // The category and its resume beside the link (2026-10-08), so the phone
        // shows which resume to send without opening anything.
        com.anuragbhandary.jobradar.domain.RoleCategory category =
                com.anuragbhandary.jobradar.domain.RoleCategory.of(companyRole[1]);
        body.append("🏷️ ").append(category.label()).append(" · ").append(category.resume().label());
        if (url != null && url.startsWith("http")) {
            body.append("\n🔗 ").append(clip(url, 300));
        }
        body.append('\n');
        if (!location.isBlank()) {
            body.append("📍 ").append(clip(location, 150)).append('\n');
        }
        if (!note.isBlank()) {
            body.append(clip(note, 1500));
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", clip(companyRole[0] + " · " + companyRole[1], 250));
        if (url != null && url.startsWith("http")) {
            card.put("url", url);
        }
        card.put("description", body.toString());
        card.put("color", colour(note));
        if (row.getPostingId() != null) {
            card.put("footer", Map.of("text", "id " + row.getPostingId()));
        }
        return card;
    }

    /**
     * Aggregators store themselves as the company. "Role @ Company" (Jobicy,
     * Arbeitnow, We Work Remotely) and "Company | ... | Role | ..." (Hacker News)
     * carry the real employer, so it is read back out.
     */
    static String[] companyAndRole(String company, String role) {
        String r = role == null ? "" : role.strip();
        int at = r.lastIndexOf(" @ ");
        if (at > 0) {
            return new String[] {r.substring(at + 3).strip(), r.substring(0, at).strip()};
        }
        if (r.contains(" | ") && company != null && company.startsWith("HN")) {
            String[] parts = r.split("\\s*\\|\\s*");
            String roleName = parts.length > 1 ? parts[1] : r;
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].startsWith("http") && !parts[i].contains(".")) {
                    roleName = parts[i];
                    break;
                }
            }
            return new String[] {parts[0], roleName};
        }
        int colon = r.indexOf(": ");
        if (colon > 0 && company != null && company.equals("We Work Remotely")) {
            return new String[] {r.substring(0, colon), r.substring(colon + 2)};
        }
        return new String[] {company == null ? "" : company, r};
    }

    /**
     * {@code mark --note} appends a line rather than replacing, so an earlier
     * verdict ("Apply now") sits above a later one ("Stretch"). The last line is
     * the current one.
     */
    static String latestNote(String notes) {
        return JobInterest.latestNote(notes);
    }

    private static int colour(String note) {
        String n = note.toLowerCase(Locale.ROOT);
        if (n.startsWith("apply")) {
            return GREEN;
        }
        if (n.startsWith("stretch")) {
            return AMBER;
        }
        return GREY;
    }

    private boolean post(String body) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(webhook))
                                .timeout(Duration.ofSeconds(20))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    Thread.sleep(700);
                    return true;
                }
                if (response.statusCode() == 429) {
                    JsonNode error = json.readTree(response.body());
                    double wait = error.path("retry_after").asDouble(2.0);
                    Thread.sleep((long) (wait * 1000) + 250);
                    continue;
                }
                System.out.println("Discord answered " + response.statusCode() + ": "
                        + clip(response.body(), 300));
                return false;
            } catch (IOException e) {
                System.out.println("Could not reach Discord: " + e.getMessage());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        System.out.println("Discord kept rate-limiting; giving up.");
        return false;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
