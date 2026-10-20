package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads a Personio careers feed: {@code https://<token>.jobs.personio.de/xml}.
 *
 * <p>Added 2026-10-20. Personio is what a large share of German and Dutch
 * startups hire through, and none of them were visible to the radar.
 *
 * <p>The feed has no country, only office names ("Munich", "Berlin"), which the
 * location classifier reads as it reads any city. The description comes in
 * named sections; all of them are kept, since the years usually sit in the last.
 */
@Component
public class PersonioFetcher implements AtsFetcher {

    static final String FEED = "https://%s.jobs.personio.de/xml?language=en";
    private static final String JOB_URL = "https://%s.jobs.personio.de/job/%s?language=en";

    private final HttpFetchClient http;

    public PersonioFetcher(HttpFetchClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.PERSONIO;
    }

    @Override
    public FetchBatch fetch(String boardToken) throws FetchException {
        return FetchBatch.of(parse(http.get(FEED.formatted(boardToken), "personio-" + boardToken), boardToken));
    }

    List<RawPosting> parse(String body, String boardToken) throws FetchException {
        Document doc = XmlFeeds.parse(body, "Personio board " + boardToken);
        if (!"workzag-jobs".equals(doc.getDocumentElement().getTagName())) {
            throw new FetchException("Board " + boardToken + " is not a Personio feed; token may be wrong");
        }
        List<RawPosting> postings = new ArrayList<>();
        NodeList positions = doc.getElementsByTagName("position");
        for (int i = 0; i < positions.getLength(); i++) {
            Element position = (Element) positions.item(i);
            String id = XmlFeeds.child(position, "id");
            String title = XmlFeeds.child(position, "name");
            if (id == null || title == null || title.isBlank()) {
                continue;
            }
            postings.add(new RawPosting(id, title.strip(), offices(position), description(position),
                    JOB_URL.formatted(boardToken, id), date(XmlFeeds.child(position, "createdAt"))));
        }
        return postings;
    }

    /** The main office and the additional ones. */
    private static String offices(Element position) {
        Set<String> all = new LinkedHashSet<>();
        String main = XmlFeeds.child(position, "office");
        if (main != null && !main.isBlank()) {
            all.add(main.strip());
        }
        NodeList extra = position.getElementsByTagName("additionalOffices");
        if (extra.getLength() > 0) {
            NodeList offices = ((Element) extra.item(0)).getElementsByTagName("office");
            for (int i = 0; i < offices.getLength(); i++) {
                String office = offices.item(i).getTextContent();
                if (office != null && !office.isBlank()) {
                    all.add(office.strip());
                }
            }
        }
        return all.isEmpty() ? null : String.join("; ", all);
    }

    /** Every named section, heading and text, tags stripped. */
    private static String description(Element position) {
        StringBuilder text = new StringBuilder();
        NodeList sections = position.getElementsByTagName("jobDescription");
        for (int i = 0; i < sections.getLength(); i++) {
            Element section = (Element) sections.item(i);
            String heading = XmlFeeds.child(section, "name");
            String value = XmlFeeds.child(section, "value");
            if (heading != null) {
                text.append(heading.strip()).append('\n');
            }
            if (value != null) {
                text.append(Html.toPlainText(value)).append("\n\n");
            }
        }
        return text.toString().strip();
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value.strip()).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Small helpers shared by the XML feeds (Personio, Teamtailor). */
    static final class XmlFeeds {

        private XmlFeeds() {
        }

        static Document parse(String body, String what) throws FetchException {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                // A feed from the internet: no DTDs, no external entities.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setExpandEntityReferences(false);
                factory.setNamespaceAware(false);
                return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            } catch (Exception e) {
                throw new FetchException("Unparseable feed from " + what, e);
            }
        }

        /** The text of the first direct child with this tag, or null. */
        static String child(Element parent, String tag) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals(tag)) {
                    return e.getTextContent();
                }
            }
            return null;
        }
    }
}
