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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code notify [--ids=a,b,c] [--title="..."] [--dry-run]} - post the shortlist to Discord.
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

    public NotifyCommand(JobInterestRepository interests, PostingRepository postings,
            HttpClient http, ObjectMapper json,
            @Value("${job-radar.notify.discord-webhook:}") String webhook) {
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

        List<JobInterest> rows = select(options.get("ids"));
        if (rows.isEmpty()) {
            System.out.println("Nothing to send: the shortlist is empty.");
            return;
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        for (JobInterest row : rows) {
            cards.add(card(row));
        }

        String title = options.getOrDefault("title",
                "job-radar · " + LocalDate.now() + " · " + cards.size() + " to apply to");
        int sent = 0;
        for (int i = 0; i < cards.size(); i += EMBEDS_PER_MESSAGE) {
            Map<String, Object> message = new LinkedHashMap<>();
            if (i == 0) {
                message.put("content", "**" + title + "**");
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
        if (notes == null) {
            return "";
        }
        String latest = "";
        for (String line : notes.split("\\R")) {
            if (!line.isBlank()) {
                latest = line.strip();
            }
        }
        return latest;
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
