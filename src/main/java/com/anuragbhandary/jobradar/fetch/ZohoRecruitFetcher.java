package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads careers sites on Zoho Recruit through their RSS feed, one portal per board.
 *
 * <p>Every Zoho Recruit portal publishes {@code /jobs/<portal>/rss}, and its
 * robots.txt allows it. Each item has the title, the job page link and a
 * description that opens with "Category: ... Location: ..." lines before the
 * job text. The feed is not always well-formed XML (stray tags in the job
 * HTML), so items are read with patterns rather than an XML parser.
 *
 * <p><b>The token</b> is {@code name/host/portal}: {@code zoho/careers.zohocorp.com/Careers}.
 */
@Component
public class ZohoRecruitFetcher implements AtsFetcher {

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>\\s*([^<\\s]+)\\s*</link>");
    private static final Pattern DESCRIPTION = Pattern.compile(
            "<description>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</description>", Pattern.DOTALL);
    /** The job id is the long number in the link: /jobs/Careers/193779000024446019/... */
    private static final Pattern JOB_ID = Pattern.compile("/(\\d{10,})(?:/|$|\\?)");
    private static final Pattern LOCATION = Pattern.compile("Location:\\s*([^<]+?)\\s*<br");

    private final HttpFetchClient http;

    public ZohoRecruitFetcher(HttpFetchClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.ZOHO_RECRUIT;
    }

    /** {@code name/host/portal}, as seeded. */
    record Board(String name, String host, String portal) {

        static Board parse(String token) throws FetchException {
            String[] parts = token == null ? new String[0] : token.split("/");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new FetchException("Zoho Recruit token must be name/host/portal, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }

        String feed() {
            return "https://%s/jobs/%s/rss".formatted(host, portal);
        }
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Board board = Board.parse(boardToken);
        return FetchBatch.of(parse(http.get(board.feed(), "zoho-recruit-" + board.name())));
    }

    /** Split out from {@link #fetch} so tests can drive it from a saved fixture. */
    List<RawPosting> parse(String feed) throws FetchException {
        if (feed == null || !feed.contains("<rss")) {
            throw new FetchException("Not a Zoho Recruit RSS feed");
        }
        List<RawPosting> postings = new ArrayList<>();
        Matcher items = ITEM.matcher(feed);
        while (items.find()) {
            String item = items.group(1);
            String title = first(TITLE, item);
            String link = first(LINK, item);
            if (title == null || link == null) {
                continue;
            }
            Matcher id = JOB_ID.matcher(link);
            String html = first(DESCRIPTION, item);
            String place = html == null ? null : first(LOCATION, html);
            postings.add(new RawPosting(
                    id.find() ? id.group(1) : link,
                    Html.toPlainText(title).trim(),
                    place == null ? "" : place.replaceAll("\\s+", " ").trim(),
                    html == null ? "" : Html.toPlainText(html),
                    link.replace("?source=RSS", ""),
                    null));
        }
        return postings;
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
