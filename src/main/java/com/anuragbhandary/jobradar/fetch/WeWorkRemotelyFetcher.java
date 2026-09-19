package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads We Work Remotely's public RSS feeds for back-end and full-stack jobs.
 *
 * <p>Items are titled "Company: Role" and carry a {@code region} such as
 * "Anywhere in the World" or "USA Only", which becomes the remote location the
 * classifier reads. The two categories overlap and are merged on the item link.
 */
@Component
public class WeWorkRemotelyFetcher implements AtsFetcher {

    public static final String BOARD = "weworkremotely";

    private static final String FEED = "https://weworkremotely.com/categories/%s.rss";
    private static final List<String> CATEGORIES =
            List.of("remote-back-end-programming-jobs", "remote-full-stack-programming-jobs");

    private final HttpFetchClient http;

    public WeWorkRemotelyFetcher(HttpFetchClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.WE_WORK_REMOTELY;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        Map<String, RawPosting> byId = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            for (RawPosting p : parse(http.get(FEED.formatted(category), "wwr-" + category))) {
                byId.putIfAbsent(p.externalId(), p);
            }
        }
        return FetchBatch.of(new ArrayList<>(byId.values()));
    }

    List<RawPosting> parse(String body) throws FetchException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A feed from the internet: no DTDs, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            throw new FetchException("Unparseable feed from We Work Remotely", e);
        }
        List<RawPosting> postings = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = text(item, "link");
            String title = text(item, "title");
            if (link == null || title == null) {
                continue;
            }
            String region = text(item, "region");
            postings.add(new RawPosting(link, title.strip(),
                    "Remote, " + (region == null ? "unspecified" : region),
                    Html.toPlainText(text(item, "description")),
                    link, date(text(item, "pubDate"))));
        }
        return postings;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
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
