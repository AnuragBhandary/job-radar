package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.PersonioFetcher.XmlFeeds;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads a Teamtailor careers site's RSS feed, {@code /jobs.rss}.
 *
 * <p>Added 2026-10-20 for the Nordic and Dutch startups that hire through it.
 * The token is the subdomain ("acme" for acme.teamtailor.com) or, for a site on
 * its own domain, the host itself ("career.teamtailor.com").
 *
 * <p>Like Recruitee, remote is a flag on a job that still has an office, so it is
 * appended to the location rather than replacing it.
 */
@Component
public class TeamtailorFetcher implements AtsFetcher {

    private final HttpFetchClient http;

    public TeamtailorFetcher(HttpFetchClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.TEAMTAILOR;
    }

    /** The feed for a subdomain token or a custom-domain host. */
    static String feed(String token) {
        String host = token.contains(".") ? token : token + ".teamtailor.com";
        return "https://" + host + "/jobs.rss";
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(feed(boardToken), "teamtailor-" + boardToken), boardToken));
    }

    List<RawPosting> parse(String body, String boardToken) throws FetchException {
        Document doc = XmlFeeds.parse(body, "Teamtailor board " + boardToken);
        if (!"rss".equals(doc.getDocumentElement().getTagName())) {
            throw new FetchException("Board " + boardToken + " is not a Teamtailor feed; token may be wrong");
        }
        List<RawPosting> postings = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = XmlFeeds.child(item, "title");
            String link = XmlFeeds.child(item, "link");
            if (title == null || title.isBlank() || link == null) {
                continue;
            }
            String guid = XmlFeeds.child(item, "guid");
            postings.add(new RawPosting(guid == null ? link.strip() : guid.strip(), title.strip(),
                    locations(item), Html.toPlainText(XmlFeeds.child(item, "description")),
                    link.strip(), date(XmlFeeds.child(item, "pubDate"))));
        }
        return postings;
    }

    /** "City, Country" for each location, then "Remote" when the job says so. */
    private static String locations(Element item) {
        Set<String> all = new LinkedHashSet<>();
        NodeList locations = item.getElementsByTagName("tt:location");
        for (int i = 0; i < locations.getLength(); i++) {
            Element location = (Element) locations.item(i);
            String city = XmlFeeds.child(location, "tt:city");
            String country = XmlFeeds.child(location, "tt:country");
            String name = city != null && !city.isBlank() ? city.strip() : XmlFeeds.child(location, "tt:name");
            if (name == null || name.isBlank()) {
                continue;
            }
            all.add(country == null || country.isBlank() ? name.strip() : name.strip() + ", " + country.strip());
        }
        String remote = XmlFeeds.child(item, "remoteStatus");
        if (remote != null && remote.toLowerCase(Locale.ROOT).matches("fully|remote|temporary")) {
            all.add("Remote");
        }
        return all.isEmpty() ? null : String.join("; ", all);
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null
                    : ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
