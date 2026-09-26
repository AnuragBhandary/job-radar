package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds the employer's own page for a role seen on an aggregator.
 *
 * <p>Arbeitnow, Jobicy and We Work Remotely link to themselves, and applying
 * through them costs an email address or a fee. The role is almost always also
 * on the employer's applicant tracking system, under a handle derived from the
 * company name, so each likely handle is tried on each platform and the job
 * whose title matches is returned.
 *
 * <p>Workable and Personio are searched here although job-radar cannot fetch
 * them as sources: many small European employers use them, and a link is all
 * that is needed.
 */
@Component
public class DirectLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(DirectLinkResolver.class);

    /** Legal-form and filler words that are never part of a board handle. */
    private static final Set<String> NOISE = Set.of("gmbh", "ag", "se", "inc", "ltd", "llc",
            "limited", "bv", "b.v.", "pvt", "private", "co", "corp", "corporation", "company",
            "group", "deutschland", "the", "hq", "1");

    private static final Pattern PERSONIO_POSITION = Pattern.compile(
            "<position>.*?<id>(\\d+)</id>.*?<name>(.*?)</name>.*?</position>", Pattern.DOTALL);

    /** Where a role was found. {@code source} is set when job-radar can fetch that board. */
    public record Found(String url, String platform, String token, Source source) {
    }

    private final HttpFetchClient http;
    private final ObjectMapper json;

    public DirectLinkResolver(HttpFetchClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    public Optional<Found> resolve(String company, String roleTitle) {
        for (String handle : handles(company)) {
            for (String platform : List.of("greenhouse", "lever", "ashby", "smartrecruiters",
                    "recruitee", "workable", "personio")) {
                try {
                    Optional<Found> found = search(platform, handle, roleTitle);
                    if (found.isPresent()) {
                        return found;
                    }
                } catch (FetchException | RuntimeException e) {
                    log.debug("{} {}: {}", platform, handle, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    /** Likely board handles for a company name, most specific first. */
    static List<String> handles(String company) {
        if (company == null) {
            return List.of();
        }
        String cleaned = company.toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s.-]", " ");
        List<String> words = new ArrayList<>();
        for (String w : cleaned.split("[\\s.]+")) {
            if (!w.isBlank() && !NOISE.contains(w)) {
                words.add(w.replace("-", ""));
            }
        }
        if (words.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(String.join("", words));
        out.add(String.join("-", words));
        out.add(words.getFirst());
        return List.copyOf(out);
    }

    private Optional<Found> search(String platform, String h, String title) throws FetchException {
        return switch (platform) {
            case "greenhouse" -> fromJson("https://boards-api.greenhouse.io/v1/boards/" + h + "/jobs",
                    root -> root.path("jobs"), "title", "absolute_url", title, platform, h,
                    Source.GREENHOUSE);
            case "lever" -> fromJson("https://api.lever.co/v0/postings/" + h + "?mode=json",
                    root -> root, "text", "hostedUrl", title, platform, h, Source.LEVER);
            case "ashby" -> fromJson("https://api.ashbyhq.com/posting-api/job-board/" + h,
                    root -> root.path("jobs"), "title", "jobUrl", title, platform, h, Source.ASHBY);
            case "recruitee" -> fromJson("https://" + h + ".recruitee.com/api/offers/",
                    root -> root.path("offers"), "title", "careers_url", title, platform, h,
                    Source.RECRUITEE);
            case "workable" -> fromJson("https://apply.workable.com/api/v1/widget/accounts/" + h,
                    root -> root.path("jobs"), "title", "url", title, platform, h, null);
            case "smartrecruiters" -> smartRecruiters(h, title);
            case "personio" -> personio(h, title);
            default -> Optional.empty();
        };
    }

    private Optional<Found> fromJson(String url, java.util.function.Function<JsonNode, JsonNode> jobs,
            String titleField, String urlField, String title, String platform, String handle,
            Source source) throws FetchException {
        HttpFetchClient.HttpResult result = http.getRaw(url, null);
        if (!result.isSuccess()) {
            return Optional.empty();
        }
        JsonNode list;
        try {
            list = jobs.apply(json.readTree(result.body()));
        } catch (Exception e) {
            return Optional.empty();
        }
        String bestUrl = null;
        double best = 0;
        for (JsonNode job : list) {
            double score = similarity(job.path(titleField).asText(), title);
            if (score > best && !job.path(urlField).asText().isBlank()) {
                best = score;
                bestUrl = job.path(urlField).asText();
            }
        }
        return best >= THRESHOLD
                ? Optional.of(new Found(bestUrl, platform, handle, source))
                : Optional.empty();
    }

    private Optional<Found> smartRecruiters(String h, String title) throws FetchException {
        HttpFetchClient.HttpResult result = http.getRaw(
                "https://api.smartrecruiters.com/v1/companies/" + h + "/postings?limit=100", null);
        if (!result.isSuccess()) {
            return Optional.empty();
        }
        try {
            String bestId = null;
            double best = 0;
            for (JsonNode job : json.readTree(result.body()).path("content")) {
                double score = similarity(job.path("name").asText(), title);
                if (score > best) {
                    best = score;
                    bestId = job.path("id").asText();
                }
            }
            return best >= THRESHOLD
                    ? Optional.of(new Found("https://jobs.smartrecruiters.com/" + h + "/" + bestId,
                            "smartrecruiters", h, Source.SMARTRECRUITERS))
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Found> personio(String h, String title) throws FetchException {
        for (String domain : List.of(".jobs.personio.de", ".jobs.personio.com")) {
            HttpFetchClient.HttpResult result = http.getRaw("https://" + h + domain + "/xml", null);
            if (!result.isSuccess() || !result.body().contains("<position>")) {
                continue;
            }
            Matcher m = PERSONIO_POSITION.matcher(result.body());
            String bestId = null;
            double best = 0;
            while (m.find()) {
                double score = similarity(m.group(2), title);
                if (score > best) {
                    best = score;
                    bestId = m.group(1);
                }
            }
            if (best >= THRESHOLD) {
                return Optional.of(new Found("https://" + h + domain + "/job/" + bestId,
                        "personio", h, null));
            }
        }
        return Optional.empty();
    }

    private static final double THRESHOLD = 0.75;

    /**
     * Word overlap of two titles after gender tags and "@ Company" are removed:
     * the share of the shorter title's words found in the longer one.
     */
    static double similarity(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> shorter = x.size() <= y.size() ? x : y;
        Set<String> longer = shorter == x ? y : x;
        long common = shorter.stream().filter(longer::contains).count();
        double overlap = (double) common / shorter.size();
        // A one-word title ("Engineer") matching inside a long one is not a match.
        double lengthRatio = (double) shorter.size() / longer.size();
        // Full credit from half the length upwards, scaled down below that.
        return overlap * Math.min(1.0, lengthRatio / 0.5);
    }

    private static Set<String> words(String title) {
        if (title == null) {
            return Set.of();
        }
        String t = title.toLowerCase(Locale.ROOT);
        int at = t.lastIndexOf(" @ ");
        if (at > 0) {
            t = t.substring(0, at);
        }
        t = t.replaceAll("\\((?:[mwfdx]\\s*/\\s*){1,3}[mwfdx]\\)|\\(all genders\\)|\\(gn\\)", " ");
        return new HashSet<>(Arrays.stream(t.split("[^\\p{L}\\p{N}]+"))
                .filter(w -> !w.isBlank())
                .toList());
    }
}
