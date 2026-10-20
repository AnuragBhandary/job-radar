package com.anuragbhandary.jobradar.sponsor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The public registers of employers allowed to sponsor a work visa, for the two
 * relocation countries where an employer needs one: the UK (Skilled Worker
 * licence) and the Netherlands (IND recognised sponsor, needed for the
 * kennismigrant permit). Germany's Blue Card and Ireland's Critical Skills
 * permit need no licensed employer, so they have no register here.
 *
 * <p>Added 2026-10-20: a third of UK picks carried "sponsorship not stated", and
 * an employer without a licence cannot hire him at all, however good the fit.
 *
 * <p>The registers are downloaded by {@code sponsors --refresh} into
 * {@code ~/.config/job-radar/sponsors/} as one name per line, so the review never
 * waits on gov.uk. A missing file means "no register", never "not licensed".
 */
@Component
public class SponsorRegister {

    /** What the register says about one employer. */
    public enum Status {
        /** On the register under the same name, give or take its legal form. */
        LICENSED,
        /**
         * Only a register name that starts with the employer's name and goes on
         * ("Quantum" against "Quantum 21 Ltd T/A PlayMeTech"): maybe the same
         * company, maybe not, so the review looks at the name.
         */
        POSSIBLE,
        /** Not on it, under any reading of the name. */
        NOT_LISTED,
        /** The board's name for the employer is a site name, not a company. */
        UNCLEAR_NAME,
        /** The register for this country has not been downloaded. */
        NO_REGISTER
    }

    public record Match(Status status, String registeredName) {
    }

    static final String UK_PAGE = "https://www.gov.uk/government/publications/register-of-licensed-sponsors-workers";
    static final String NL_PAGE =
            "https://ind.nl/en/public-register-recognised-sponsors/public-register-regular-labour-and-highly-skilled-migrants";

    /** Countries with a register, and the file each is kept in. */
    private static final Map<String, String> FILES = Map.of("GB", "gb.txt", "NL", "nl.txt");

    /**
     * Words a register name carries that the board's name for the company does
     * not: legal forms, "UK", and the generic tails ("Palantir Technologies UK
     * Limited" is Palantir). Dropped from the end only.
     */
    private static final Set<String> TAIL_WORDS = Set.of(
            "limited", "ltd", "plc", "llp", "llc", "lp", "inc", "incorporated", "corp", "corporation",
            "co", "company", "uk", "gb", "bv", "b", "v", "nv", "n", "gmbh", "ag", "sa", "se", "lda",
            "holdings", "holding", "group", "europe", "emea", "international", "operations",
            "services", "service", "technologies", "technology", "solutions", "the", "and", "com",
            // US banks' UK branches: "JPMorgan Chase Bank, N.A.", "... National Association".
            "na", "bank", "national", "association");

    /**
     * Board labels that name a careers site rather than an employer: Oracle and
     * Workday sites called "Candidate Experience site" carried Goldman Sachs and
     * Sainsbury's roles on 2026-10-20. Such a name proves nothing either way.
     */
    private static final Pattern SITE_NAME = Pattern.compile(
            "candidate experience|careers?\\b|\\bjobs?\\b|\\balumni\\b|interim acquisition|^early$|job site",
            Pattern.CASE_INSENSITIVE);

    private final Path dir;
    /** Country to (squashed name without its tail words, to registered name). */
    private final Map<String, Map<String, String>> exact = new HashMap<>();
    /** Country to (squashed leading run of words, to registered name). */
    private final Map<String, Map<String, String>> prefixes = new HashMap<>();

    public SponsorRegister(
            @Value("${job-radar.sponsors.dir:${user.home}/.config/job-radar/sponsors}") String dir) {
        this.dir = Path.of(dir.replaceFirst("^~", System.getProperty("user.home")));
        FILES.forEach((country, file) -> load(country, this.dir.resolve(file)));
    }

    /** Empty when the country has no register at all (Germany, Ireland, India). */
    public Optional<Match> check(String countryCode, String employer) {
        if (countryCode == null || !FILES.containsKey(countryCode)) {
            return Optional.empty();
        }
        Map<String, String> whole = exact.get(countryCode);
        if (whole == null) {
            return Optional.of(new Match(Status.NO_REGISTER, null));
        }
        if (employer == null || employer.isBlank() || SITE_NAME.matcher(employer).find()) {
            return Optional.of(new Match(Status.UNCLEAR_NAME, null));
        }
        List<String> keys = employerKeys(employer);
        for (String key : keys) {
            String hit = whole.get(key);
            if (hit != null) {
                return Optional.of(new Match(Status.LICENSED, hit));
            }
        }
        for (String key : keys) {
            String hit = prefixes.get(countryCode).get(key);
            if (hit != null) {
                return Optional.of(new Match(Status.POSSIBLE, hit));
            }
        }
        return Optional.of(new Match(Status.NOT_LISTED, null));
    }

    /** How many names each loaded register holds, for {@code sponsors}. */
    public Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new HashMap<>();
        FILES.forEach((country, file) -> {
            try {
                Path p = dir.resolve(file);
                sizes.put(country, Files.exists(p) ? (int) Files.lines(p).count() : 0);
            } catch (IOException e) {
                sizes.put(country, 0);
            }
        });
        return sizes;
    }

    public Path dir() {
        return dir;
    }

    /** Downloads both registers and reloads them. Returns the names kept per country. */
    public Map<String, Integer> refresh() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        Files.createDirectories(dir);
        Map<String, Integer> kept = new HashMap<>();

        String page = get(http, UK_PAGE);
        Matcher csv = Pattern.compile("https://assets\\.publishing\\.service\\.gov\\.uk/[^\"]+\\.csv").matcher(page);
        if (!csv.find()) {
            throw new IOException("No CSV link on " + UK_PAGE);
        }
        List<String> uk = skilledWorkerNames(get(http, csv.group()));
        Files.write(dir.resolve(FILES.get("GB")), uk, StandardCharsets.UTF_8);
        kept.put("GB", uk.size());

        List<String> nl = recognisedSponsorNames(get(http, NL_PAGE));
        Files.write(dir.resolve(FILES.get("NL")), nl, StandardCharsets.UTF_8);
        kept.put("NL", nl.size());

        FILES.forEach((country, file) -> load(country, dir.resolve(file)));
        return kept;
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2)).header("User-Agent", "job-radar").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    /** The organisation names on the UK register licensed for the Skilled Worker route. */
    static List<String> skilledWorkerNames(String csv) {
        Set<String> names = new LinkedHashSet<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) {
            List<String> fields = csvFields(lines[i]);
            if (fields.size() >= 5 && fields.get(4).strip().equals("Skilled Worker")) {
                names.add(fields.get(0).strip());
            }
        }
        return new ArrayList<>(names);
    }

    /** One CSV line's fields, honouring quotes ("Smith, Jones & Co"). */
    static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** The IND page lists each sponsor as a row header: {@code <th scope="row">Name</th>}. */
    static List<String> recognisedSponsorNames(String html) {
        Set<String> names = new LinkedHashSet<>();
        Matcher row = Pattern.compile("<th scope=\"row\">(.*?)</th>", Pattern.DOTALL).matcher(html);
        while (row.find()) {
            String name = row.group(1).replaceAll("<[^>]+>", "")
                    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                    .replace("\"\"", "\"").strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private void load(String country, Path file) {
        if (!Files.exists(file)) {
            exact.remove(country);
            prefixes.remove(country);
            return;
        }
        Map<String, String> whole = new HashMap<>();
        Map<String, String> runs = new HashMap<>();
        try {
            for (String name : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                for (List<String> words : variants(name)) {
                    // Every leading run is a prefix; one followed only by tail words
                    // is the name itself ("WNS Global Services" of "... (UK) Ltd").
                    StringBuilder run = new StringBuilder();
                    for (int i = 0; i < words.size(); i++) {
                        run.append(words.get(i));
                        runs.putIfAbsent(run.toString(), name);
                        if (TAIL_WORDS.containsAll(words.subList(i + 1, words.size()))) {
                            whole.putIfAbsent(run.toString(), name);
                        }
                    }
                }
            }
        } catch (IOException e) {
            return;
        }
        exact.put(country, whole);
        prefixes.put(country, runs);
    }

    /**
     * The names a register row goes by, as words: the row itself, the trading
     * name after "t/a" ("SCA Investments t/a Gousto"), and the row without a
     * leading initial ("J Sainsbury Plc"). Squashed later, so a board token
     * written as one word ("PAConsulting", "WNSGlobalServices144") still finds it.
     */
    static List<List<String>> variants(String name) {
        List<List<String>> out = new ArrayList<>();
        String[] parts = name.split("(?i)\\s+(?:t/a|trading as)\\s+");
        for (String part : parts) {
            List<String> words = words(part);
            if (words.isEmpty()) {
                continue;
            }
            out.add(words);
            int i = 0;
            while (i < words.size() - 1 && words.get(i).length() == 1) {
                i++;
            }
            if (i > 0) {
                out.add(words.subList(i, words.size()));
            }
        }
        return out;
    }

    private static List<String> withoutTail(List<String> words) {
        List<String> trimmed = new ArrayList<>(words);
        while (trimmed.size() > 1 && TAIL_WORDS.contains(trimmed.getLast())) {
            trimmed.removeLast();
        }
        return trimmed;
    }

    /** The employer's name, squashed, whole and without its tail words or a trailing number. */
    static List<String> employerKeys(String employer) {
        List<String> words = words(employer.replaceAll("\\d+$", ""));
        List<String> keys = new ArrayList<>();
        keys.add(String.join("", words));
        keys.add(String.join("", withoutTail(words)));
        return keys.stream().filter(k -> k.length() >= 3).distinct().toList();
    }

    private static List<String> words(String name) {
        // "Sainsbury's" is Sainsbury: the possessive is not part of the name.
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("['’]s\\b", "")
                .replace("&", " and ").replaceAll("[^a-z0-9]+", " ").strip();
        if (s.isEmpty()) {
            return List.of();
        }
        // Initials split by the punctuation join up again: "N.A." is "na", "B.V." "bv".
        List<String> words = new ArrayList<>();
        for (String w : s.split(" ")) {
            if (w.length() == 1 && !words.isEmpty() && words.getLast().length() == 1
                    && Character.isLetter(w.charAt(0)) && Character.isLetter(words.getLast().charAt(0))) {
                words.set(words.size() - 1, words.getLast() + w);
            } else {
                words.add(w);
            }
        }
        if (words.size() > 1 && words.getFirst().equals("the")) {
            words.removeFirst();
        }
        return words;
    }
}
