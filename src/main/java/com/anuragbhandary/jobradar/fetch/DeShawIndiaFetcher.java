package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads D. E. Shaw India's careers site, one board.
 *
 * <p>The sitemap lists every opening as {@code /careers/<slug>-<id>}, beside the
 * site's other careers pages (culture, FAQ). The JSON-LD address is empty and
 * every opening is in India, so the place is the offices the text names
 * (Hyderabad, Bengaluru, Gurugram), or India when it names none. Its
 * {@code datePosted} is the time the page was served, so no date is taken.
 */
@Component
public class DeShawIndiaFetcher extends JsonLdSitemapFetcher {

    public static final String BOARD = "deshawindia";

    private static final Pattern JOB_URL = Pattern.compile(
            "(https://www\\.deshawindia\\.com/careers/([A-Za-z0-9-]+?)-(\\d+))");

    private static final Pattern OFFICE = Pattern.compile(
            "\\b(Hyderabad|Bengaluru|Bangalore|Gurugram|Gurgaon|Mumbai)\\b");

    public DeShawIndiaFetcher(HttpFetchClient http, ObjectMapper json, TitleFilter titleFilter,
            StoredPostings stored) {
        super(http, json, titleFilter, stored);
    }

    @Override
    public Source source() {
        return Source.DESHAW;
    }

    @Override
    protected String sitemapUrl() {
        return "https://www.deshawindia.com/sitemap.xml";
    }

    @Override
    protected Pattern jobUrl() {
        return JOB_URL;
    }

    @Override
    protected String place(JsonNode posting, String text) {
        return offices(text);
    }

    /** The offices the text names, each with India, or India alone. */
    static String offices(String text) {
        Set<String> offices = new LinkedHashSet<>();
        Matcher m = OFFICE.matcher(text);
        while (m.find()) {
            offices.add(m.group(1) + ", India");
        }
        return offices.isEmpty() ? "India" : String.join("; ", offices);
    }
}
