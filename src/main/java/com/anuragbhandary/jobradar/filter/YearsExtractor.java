package com.anuragbhandary.jobradar.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the required years of experience out of a job description.
 *
 * <p>The rule is: find every plausible statement of a number of years in the
 * <em>required</em> part of the description, and take the smallest. Taking the
 * smallest is deliberately generous - a posting saying "1-3 years" should read
 * onto a year of internships - and it was checked against the real corpus before
 * being trusted: of 3,997 postings that mention a number of years, only 21 (0.5%)
 * contain both a number below 2 and a number of 5 or more, which is the case
 * where a stray small number could turn a genuine rejection into an acceptance.
 * Every one of those 21 was a Program Manager, Director or Team Lead title that
 * the title filter rejects first.
 *
 * <p>The patterns below come from the same corpus rather than from imagination.
 * All of these occur in real postings and all must parse to the same minimum:
 * {@code 3+ years}, {@code 5-8 years}, {@code 3–4 years}, {@code 4 to 8 years},
 * {@code 2-12+ years}, {@code 6 - 10 years}, {@code 5 + years}, {@code 3+ yrs},
 * {@code 0 to 2+ years' experience}.
 */
@Component
public class YearsExtractor {

    /**
     * Above this, the number is not an entry requirement - it is company history
     * ("the first database provider to IPO in over 20 years") or a benefit.
     */
    private static final int IMPLAUSIBLE_YEARS = 15;

    /**
     * A leading number, an optional range, and a years unit.
     *
     * <p>Group 1 is the lower bound, which is the one that matters. The optional
     * group after it swallows the upper bound of a range so that "5-8 years"
     * yields 5 rather than also matching 8 separately.
     *
     * <p>The number has to start where its digits start. Without that, JPMorgan's
     * "Our history spans over 200 years" matched as "00 years", and the 0 undercut
     * the "4+ years" its posting required (2026-10-01).
     */
    private static final Pattern YEARS = Pattern.compile(
            // A decimal reads as its whole part: "2.5+ years" is 2, and before
            // 2026-10-01 it read as 5, the digits after the point.
            // Only a digit before the point makes it a decimal: scraped text often
            // has no space after a full stop ("related field.5+ years").
            "(?<!\\d)(?<!\\d[.,])(\\d{1,2})(?:[.,]\\d)?\\s*\\+?\\s*"
                    + "(?:(?:[-–—]|to)\\s*\\d{1,2}\\s*\\+?\\s*)?"
                    + "(years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Amazon's phrasing, and disqualifying whatever else the posting says.
     * Internship time is the entirety of the experience being screened for, so a
     * posting that excludes it excludes this candidate no matter what number it
     * quotes - or whether it quotes one at all.
     *
     * <p>Three real phrasings have to match, and only one of them carries a
     * number:
     * <pre>
     *   1+ years of non-internship professional software development experience
     *   Experience (non-internship) in professional software development
     *   Experience in professional, non-internship software development
     * </pre>
     * The second and third are Amazon's SDE II wording and used to escape
     * entirely, because the old pattern required {@code \d+ years of} in front.
     * That single gap put 26 mid-level AWS roles - Firecracker, Shield, RDS
     * Platform - into the candidate list, 30% of everything it contained.
     *
     * <p>So the number is gone from the pattern and proximity replaces it: the
     * phrase counts only when it sits within a clause of "professional" or
     * "experience". Matching the bare token everywhere would be simpler and
     * wrong - "internship and non-internship candidates welcome" is an invitation,
     * not a requirement, and reads as the exact opposite of what it is.
     */
    private static final Pattern NON_INTERNSHIP = Pattern.compile(
            "(?:professional|experience)[^.]{0,40}?non-?\\s?internship"
                    + "|non-?\\s?internship[^.]{0,40}?(?:professional|experience)"
                    // "2+ years of full-time software engineering experience",
                    // "experience (full-time)". The word right before
                    // "experience" is the test, so "a full-time role; experience
                    // with Kafka" does not count.
                    + "|full[-\\s]?time\\s+(?:professional\\s+|industry\\s+|work\\s+|software\\s+"
                    + "|engineering\\s+|development\\s+|paid\\s+){0,3}experience"
                    + "|experience\\s*\\(full[-\\s]?time\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where the requirements stop and the wishlist starts.
     *
     * <p>Everything after this heading is what the employer would <em>like</em>,
     * and its numbers must not be read as the bar. Amazon's Network Dev Engineer
     * I in Bengaluru required "2+ years of IT Security experience" and preferred
     * "1+ years of automation scripting" - so taking the smallest number in the
     * whole document read the requirement as 1, cleared the entry-level gate and
     * shipped a two-year role as a candidate.
     *
     * <p>This is a different failure from the one measured above: not a small
     * number against a large one, but a preference against a requirement. Four
     * candidates were affected.
     */
    private static final Pattern PREFERRED_SECTION = Pattern.compile(
            // Not "Education & Preferred Qualifications": State Street's heading
            // for its required degree and years (2026-09-28, "8+ years" read as
            // a wish, and an eight-year role reached review as entry level).
            "(?<!education\\s{0,3}(?:&|and)\\s{0,3})"
                    + "\\b(?:preferred\\s+qualifications|preferred\\s+skills|preferred\\s*:"
                    + "|nice[\\s-]to[\\s-]have|bonus\\s+points|good\\s+to\\s+have"
                    + "|desired\\s+qualifications)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where the requirements <em>start</em>, when the posting says so.
     *
     * <p>Anchoring on this matters more than it looks. Stripe's descriptions
     * invite you to apply "even if you don't meet all the preferred
     * qualifications" - in prose, 57 characters before the real "Minimum
     * requirements" heading. Cutting at the first preferred marker therefore
     * discarded the requirements section entirely and read those postings as
     * stating nothing, which turned 6-, 8- and 10-year roles into candidates.
     *
     * <p>So the last of these headings wins - prose mentions come before the
     * heading, not after it - and the wishlist is then looked for beyond it.
     */
    private static final Pattern REQUIRED_SECTION = Pattern.compile(
            "\\b(?:minimum\\s+requirements|minimum\\s+qualifications"
                    + "|basic\\s+qualifications|required\\s+qualifications"
                    + "|what\\s+you.{0,3}ll\\s+need|requirements\\s*:)",
            Pattern.CASE_INSENSITIVE);

    /**
     * More requirement headings, consulted only when the span found with
     * {@link #REQUIRED_SECTION} states no years at all.
     *
     * <p>From postings whose years were missed on 2026-10-01: AlphaSense's
     * "Foundational Requirements 4+ years", Deliveroo's "Our expectations", and the
     * "Qualifications:" section the Oracle fetcher appends after a description
     * whose prose already said "good to have". They are a fallback rather than
     * more headings of the same rank because the first version ranked them
     * equally: any of them appearing switched the top of the description into the
     * stricter above-the-headings reading, and "Experience: 6 - 9 Years" lines
     * stopped counting on 100 postings.
     */
    private static final Pattern FALLBACK_REQUIRED_SECTION = Pattern.compile(
            "\\b(?:(?:foundational|core|key|essential|job)\\s+requirements"
                    + "|our\\s+expectations|who\\s+you\\s+are|what\\s+we.{0,3}re\\s+looking\\s+for"
                    + "|must[\\s-]haves?|successful\\s+candidates?\\s+will\\s+have"
                    + "|(?<!(?:preferred|desired|additional|bonus|optional|nice)\\s{1,3})qualifications\\s*:)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Below this, a split is assumed to be spurious rather than structural.
     * A description opening on "Preferred Qualifications" has not told us what is
     * required; it has been formatted in a way this does not understand, and
     * reading the whole thing is the safer failure.
     *
     * <p>Kept small because Amazon's bullet lists are terse: posting 8377's two
     * required lines run to 96 characters before "Preferred:" arrives.
     */
    private static final int MIN_REQUIRED_LENGTH = 30;

    /** A programme or contract word straight after a number: a duration, not a bar. */
    private static final Pattern DURATION_AFTER = Pattern.compile(
            "^\\W{0,3}(?:program|programme|rotation|rotational|contract|fixed|term)",
            Pattern.CASE_INSENSITIVE);

    /**
     * A years requirement stated in the title, as in PhonePe's "Site Reliability
     * Engineer - AWS (4 to 8 Years)", whose description never repeats it.
     *
     * <p>Narrower than {@link #extract}, because a false positive here rejects a
     * whole role on thirty characters of evidence. Only a plural or range form
     * counts: a singular "2 Year" in a title is a duration ("Software Engineer,
     * 2 Year Rotational Programme"), and a number followed by a programme or
     * contract word is never read as a requirement.
     */
    public YearsExtraction extractFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return YearsExtraction.none();
        }
        title = normaliseSpaces(title);
        int min = YearsExtraction.NONE_STATED;
        Matcher m = YEARS.matcher(title);
        while (m.find()) {
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("year") || unit.equals("yr")) {
                continue;
            }
            String after = title.substring(m.end(), Math.min(title.length(), m.end() + 20));
            if (DURATION_AFTER.matcher(after).find()) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years < IMPLAUSIBLE_YEARS && (min == YearsExtraction.NONE_STATED || years < min)) {
                min = years;
            }
        }
        return min == YearsExtraction.NONE_STATED ? YearsExtraction.none()
                : new YearsExtraction(min, title.replaceAll("\\s+", " ").strip(), null);
    }

    public YearsExtraction extract(String description) {
        if (description == null || description.isBlank()) {
            return YearsExtraction.none();
        }
        description = normaliseSpaces(description);

        // Searched across the whole description: Amazon states it in the basic
        // qualifications, but a posting that excludes internship experience
        // anywhere has excluded it.
        String nonInternship = null;
        Matcher exclusion = NON_INTERNSHIP.matcher(description);
        if (exclusion.find()) {
            nonInternship = phrase(description, exclusion.start(), exclusion.end());
        }

        String required = requiredSection(description);

        // Minimums over four pools: every number, the numbers nobody hedged, and
        // each of those narrowed to statements about experience overall. A hedge
        // may stop a smaller number undercutting a stated requirement, but it
        // never makes a posting read as stating nothing - Philips puts its only
        // requirement behind "Preferably 6+ years", and skipping that turned a
        // six-year role into a candidate.
        //
        // The overall statement wins over one about a single tool (2026-10-01):
        // Deliveroo's "3 to 6 years of professional software engineering
        // experience" read as 1 from "1+ years of production experience in
        // Golang", and Sutherland's "3-7 years of experience in Data Analytics"
        // as 2 from "2 years of hands-on experience with Microsoft Fabric". A
        // tool's number counts only when no overall one is stated.
        Pool all = new Pool();
        Pool plain = new Pool();
        Pool allOverall = new Pool();
        Pool plainOverall = new Pool();

        Matcher m = YEARS.matcher(required);
        while (m.find()) {
            if (isProgrammeDuration(required, m) || isDegreeAlternative(required, m)
                    || isUpperBound(required, m) || isCompanyTime(required, m)) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            String here = phrase(required, m.start(), m.end());
            boolean overall = !isAboutOneTool(required, m);
            boolean hedged = isOptional(required, m);
            all.offer(years, here);
            if (overall) {
                allOverall.offer(years, here);
            }
            if (!hedged) {
                plain.offer(years, here);
                if (overall) {
                    plainOverall.offer(years, here);
                }
            }
        }
        // The qualification line, wherever it sits. Bosch writes it last, after its
        // "Good to Have" heading, so requiredSection() cut it off entirely: "B.E 3
        // to 6 years experience" and "BE, ME - Electronics background 4 to 7 years"
        // both read as stating nothing, and a three- and a four-year role reached
        // the digest as entry level.
        Matcher tail = YEARS.matcher(description);
        while (tail.find()) {
            if (isProgrammeDuration(description, tail) || isOptional(description, tail)
                    || isDegreeAlternative(description, tail) || isUpperBound(description, tail)
                    || !isQualification(description, tail)) {
                continue;
            }
            int years = Integer.parseInt(tail.group(1));
            if (years >= IMPLAUSIBLE_YEARS) {
                continue;
            }
            String here = phrase(description, tail.start(), tail.end());
            plain.offer(years, here);
            plainOverall.offer(years, here);
        }
        // No unit at all. Bosch's SmartRecruiters postings end on the degree line
        // and then a bare "2-10" or "5-10" on its own line; YEARS needs the word
        // "years", so a five-year Quality Engineer and three two-year Java roles
        // read as stating nothing (2026-09-29).
        Matcher bare = BARE_RANGE_LINE.matcher(description);
        while (bare.find()) {
            if (!DEGREE_LINE.matcher(previousLine(description, bare.start())).find()) {
                continue;
            }
            int years = Integer.parseInt(bare.group(1));
            if (years < IMPLAUSIBLE_YEARS) {
                String here = phrase(description, bare.start(), bare.end());
                plain.offer(years, here);
                plainOverall.offer(years, here);
            }
        }

        Pool chosen = !plain.isEmpty()
                ? (plainOverall.isEmpty() ? plain : plainOverall)
                : (allOverall.isEmpty() ? all : allOverall);
        // Last resort: a bar stated in so many words, wherever it sits. Only when
        // nothing else was read, so it can never lower a number found above; the
        // first version fed these into the pools and read a Qualcomm 6-year role as
        // one year from its degree-tier line (2026-10-07).
        if (chosen.isEmpty()) {
            chosen = statedBars(description);
        }
        return new YearsExtraction(chosen.min, chosen.evidence, nonInternship);
    }

    /** "for the past 12 years", "Over the next 5 years", "in just 8 years". */
    private static final Pattern COMPANY_TIME_BEFORE = Pattern.compile(
            "\\b(?:past|last|next|coming|over\\s+the|for\\s+(?:over|more\\s+than|nearly|almost)"
                    + "|in\\s+(?:just|only|under)|within\\s+the|during\\s+the|since|founded|celebrating"
                    // A contract's length: "Contract role for 1.5 years".
                    + "|(?:contract|role|assignment|engagement|duration|period)\\s+(?:of|for)"
                    + "|travel\\s+extensively\\s+for)"
                    + "\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "12 years in a row", "5 years running", "25 years of history". */
    private static final Pattern COMPANY_TIME_AFTER = Pattern.compile(
            "^\\W{0,3}(?:in\\s+a\\s+row|running|ago|old\\b|of\\s+(?:history|service|operation|operations"
                    + "|growth|excellence|innovation|heritage|trust|success)\\b|anniversary)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Time the company talks about, not experience it asks for. The overall
     * requirement now outranks a number about one tool, so a stray "a Top
     * Workplace for the past 12 years" outranked a real "2 years with SQL" and
     * rejected six roles on the first pass (2026-10-01).
     */
    private static boolean isCompanyTime(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 25), m.start())
                .replaceAll("[\\r\\n\\t]+", " ");
        String after = text.substring(m.end(), Math.min(text.length(), m.end() + 30));
        return COMPANY_TIME_BEFORE.matcher(before).find() || COMPANY_TIME_AFTER.matcher(after).find();
    }

    /** The smallest number offered, and the words around it. */
    private static final class Pool {
        int min = YearsExtraction.NONE_STATED;
        String evidence;

        void offer(int years, String here) {
            if (min == YearsExtraction.NONE_STATED || years < min) {
                min = years;
                evidence = here;
            }
        }

        boolean isEmpty() {
            return min == YearsExtraction.NONE_STATED;
        }
    }

    /**
     * Experience with one named tool rather than overall: "1+ years of production
     * experience in Golang", "2 years of hands-on experience with Microsoft
     * Fabric", "(2+ year) working with relational databases". "Experience in" a
     * field (software, data, analytics, a similar role) is overall.
     */
    private static final Pattern ONE_TOOL_AFTER = Pattern.compile(
            "^[^.;\\n]{0,50}?\\b(?:"
                    + "(?:experience|exposure|expertise|proficiency|knowledge)\\s+(?:with|using|on)\\b"
                    + "|(?:working|hands-on|hands\\s+on)\\s+(?:experience\\s+)?(?:with|on|in|using)\\b"
                    + "|(?:experience|expertise)\\s+in\\s+(?!(?:a|an|the|software|data|analytics|backend"
                    + "|back-end|engineering|development|it|technology|tech|similar|related|relevant"
                    + "|industry|professional|programming|computer|product|quantitative|machine|ml|ai"
                    + "|business|qa|quality|testing|devops|cloud|infrastructure|platform|web"
                    + "|applications?|financial|fintech|this|such|one|any|building|designing"
                    + "|developing|delivering|writing|working)\\b))",
            Pattern.CASE_INSENSITIVE);

    /**
     * Part of a larger requirement: "5+ years of software engineering experience,
     * including 1-2 years on LLM-powered systems". ClickHouse's AI Product
     * Engineer read as a one-year role on the part (2026-10-07).
     */
    private static final Pattern PART_BEFORE = Pattern.compile(
            "\\b(?:including|incl\\.?|of\\s+which)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Experience counted as a whole, whatever field follows: "Minimum of 5 years of
     * cumulative experience in Site Reliability Engineering". "Experience in" a
     * field this does not list read as one tool, and Ryan Specialty's five-year
     * SRE role read as one year from a Bash line beside it (2026-10-07).
     */
    private static final Pattern OVERALL_AFTER = Pattern.compile(
            "^\\W{0,3}(?:of\\s+)?(?:cumulative|total|overall)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean isAboutOneTool(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 20), m.start());
        if (PART_BEFORE.matcher(before).find()) {
            return true;
        }
        String after = text.substring(m.end(), Math.min(text.length(), m.end() + 90));
        if (OVERALL_AFTER.matcher(after).find()) {
            return false;
        }
        return ONE_TOOL_AFTER.matcher(after).find();
    }

    /**
     * The part of the description that states what is actually required.
     *
     * <p>Two shapes, because boards use both. When the posting names its
     * requirements section, the span runs from the last such heading to the first
     * wishlist heading after it. When it does not - Amazon just opens with
     * bullets - everything before the first wishlist heading is taken instead.
     */
    private static String requiredSection(String description) {
        String span = headedSection(description);
        if (YEARS.matcher(span).find()) {
            return span;
        }
        List<Integer> fallbacks = new ArrayList<>();
        Matcher heading = FALLBACK_REQUIRED_SECTION.matcher(description);
        while (heading.find()) {
            fallbacks.add(heading.start());
        }
        for (int i = fallbacks.size() - 1; i >= 0; i--) {
            String candidate = spanFrom(description, fallbacks.get(i));
            if (YEARS.matcher(candidate).find()) {
                return candidate;
            }
        }
        return span;
    }

    private static String headedSection(String description) {
        // The top of the description is tried last: Rubrik's "Experience You'll
        // Need: 2+ years" and Planet's "What You Bring: 2+ years" sit above the
        // only heading this recognises.
        List<Integer> headings = new ArrayList<>();
        headings.add(0);
        Matcher required = REQUIRED_SECTION.matcher(description);
        while (required.find()) {
            if (required.start() > 0) {
                headings.add(required.start());
            }
        }

        // The last heading wins, unless its span states no years at all and an
        // earlier one does. Boilerplate near the end ("Export Control
        // Requirements:", a drug-policy "Requirements:") used to win and hide the
        // real line: Wells Fargo, Rubrik and Planet each stated "2+ years" under
        // an earlier heading and read as stating nothing (2026-09-28).
        String fallback = null;
        for (int i = headings.size() - 1; i >= 0; i--) {
            String span = spanFrom(description, headings.get(i));
            if (fallback == null) {
                fallback = span;
            }
            // Above every heading is where the company talks about itself ("For 10
            // years, Scale has...", "Over the next 3 years, Twilio is..."). There a
            // number counts only when it is plainly about experience; the first
            // version of this fallback rejected a Scale AI new-grad role on it.
            boolean aboveHeadings = i == 0 && headings.size() > 1;
            if (aboveHeadings ? statesExperience(span) : YEARS.matcher(span).find()) {
                return span;
            }
        }
        return fallback;
    }

    private static final Pattern EXPERIENCE_AFTER = Pattern.compile(
            "^[^.;\\n]{0,40}\\b(?:experience|exp|hands-on|professional|industry)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A years statement followed closely by the word it is about: experience. */
    private static boolean statesExperience(String text) {
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            String after = text.substring(m.end(), Math.min(text.length(), m.end() + 60));
            if (EXPERIENCE_AFTER.matcher(after).find()) {
                return true;
            }
        }
        return false;
    }

    /** From a heading to the first wishlist heading after it. */
    private static String spanFrom(String description, int start) {
        Matcher preferred = PREFERRED_SECTION.matcher(description);
        int end = description.length();
        if (preferred.find(start)) {
            end = preferred.start();
        }

        // A span too short to hold a requirement means the headings were not
        // where this expected them. Reading the whole description is the safer
        // failure: it can only make the filter stricter, never more permissive.
        return end - start >= MIN_REQUIRED_LENGTH
                ? description.substring(start, end)
                : description;
    }

    /**
     * Unicode spaces as plain ones. BlackRock writes "4+ years" with a
     * narrow no-break space, which {@code \s} does not match, so a four-year
     * role read as stating nothing (2026-09-28).
     */
    private static String normaliseSpaces(String text) {
        return normaliseNumbers(
                text.replaceAll("[\\u00A0\\u2007\\u2009\\u200A\\u202F\\u205F\\u3000]", " "));
    }

    private static final List<String> NUMBER_WORDS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

    /**
     * A number written as a word, with or without its digits after it: "At least
     * three years of experience" (Millennium), "Four years of experience means"
     * (ShyftLabs), "FIVE (5) to EIGHT (8) years" (CACI). All read as stating
     * nothing until 2026-10-01.
     */
    private static final Pattern WORD_NUMBER = Pattern.compile(
            "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b(?:\\s*\\(\\d{1,2}\\))?"
                    + "(?=\\s*\\+?\\s*(?:(?:[-–—]|to)\\s*(?:\\d{1,2}|zero|one|two|three|four|five|six"
                    + "|seven|eight|nine|ten)(?:\\s*\\(\\d{1,2}\\))?\\s*\\+?\\s*)?(?:years?|yrs?)\\b"
                    + "([^.;\\n]{0,40}))",
            Pattern.CASE_INSENSITIVE);

    /** Digits after a digit, as in "5 (5) years" once the word is replaced. */
    private static final Pattern REPEATED_DIGITS = Pattern.compile("(\\d{1,2})\\s*\\(\\1\\)");

    /**
     * Word numbers as digits, but only where the years are plainly experience:
     * "over the last three years" stays prose and is never read as a bar.
     */
    static String normaliseNumbers(String text) {
        Matcher m = WORD_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String after = m.group(2) == null ? "" : m.group(2);
            String replacement = m.group();
            // The lookahead is not consumed, so in "FIVE (5) to EIGHT (8) years" the
            // second word is found on its own and sees the same "years of experience".
            if (EXPERIENCE_WORD.matcher(after).find()) {
                replacement = Integer.toString(NUMBER_WORDS.indexOf(m.group(1).toLowerCase(Locale.ROOT)));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return REPEATED_DIGITS.matcher(out).replaceAll("$1");
    }

    private static final Pattern EXPERIENCE_WORD = Pattern.compile(
            "\\b(?:experience|exp|hands-on|professional|industry)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Distinguishes "a 2-year development programme" from "2 years of experience".
     *
     * <p>A hyphen directly before a singular "year" makes it an adjective
     * describing how long something lasts, not a requirement. This matters more
     * than its rarity suggests: Celonis advertises a graduate fast-track as a
     * "2-year development programme", and reading that as two years of required
     * experience would reject precisely the kind of role being searched for.
     */
    private static boolean isProgrammeDuration(String text, Matcher m) {
        if (!m.group(2).equalsIgnoreCase("year")) {
            return false;
        }
        // Look at the character immediately before the unit word.
        int unitStart = text.lastIndexOf(m.group(2), m.end());
        return unitStart > 0 && text.charAt(unitStart - 1) == '-';
    }

    /**
     * A hedge word shortly before a number, with no colon between them.
     *
     * <p>The colon matters: "Ideally you'd have: 4+ years" is Scale AI's heading for
     * its requirements, and "Preferred qualifications: 5+ years" opens a section.
     * A hedge that introduces a list is a heading, not a softener of the number.
     */
    private static final Pattern OPTIONAL_BEFORE = Pattern.compile(
            "\\b(?:optionally|optional|ideally|preferably|preferred|bonus|a plus|nice to have)\\b"
                    + "[^.;:\\n]{0,25}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Distinguishes "optionally 1-2 years of Product Owner exposure" from the bar.
     *
     * <p>Taking the smallest number is only generous when every number is a
     * requirement. Bosch's "SW developer :Java" (2026-09) required "3-5 years of
     * professional experience in Java" and then, in the same paragraph with no
     * heading between, "optionally 1-2 years of experience ... as Product Owner".
     * The optional 1 won, and a three-year role was recommended as entry level.
     * A hedge placed in front of the number is the only signal there is, so this
     * reads the few words before it.
     */
    private static boolean isOptional(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 40), m.start());
        return OPTIONAL_BEFORE.matcher(before).find();
    }

    /**
     * The qualification line's own vocabulary: a degree, a named background, or an
     * explicit count of years required.
     *
     * <p>Deliberately not "N years of experience", which is how every wishlist line
     * is written too: "NICE TO HAVES: - 2-4 years of experience as a Software
     * Engineer" is not a bar, and anchoring on the generic phrase rejected a real
     * candidate on it.
     */
    private static final Pattern QUALIFICATION_BEFORE = Pattern.compile(
            "\\b(?:b\\.?e|b\\.?tech|m\\.?e|m\\.?tech|b\\.?sc|m\\.?sc|bachelor'?s?|master'?s?"
                    + "|background|no\\.?\\s*of\\s+years\\s+of\\s+experience(?:\\s+required)?)\\b"
                    + "[^.;\\n]{0,30}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * An alternative to a degree, not a requirement in its own right.
     *
     * <p>"Bachelor's degree, or 4+ years of equivalent experience" is Amazon's and
     * Google's standard phrasing and means the opposite of a four-year bar for
     * somebody who has the degree. Without this guard the rule below would reject
     * exactly the entry-level roles it exists to protect.
     */
    private static final Pattern ALTERNATIVE_TO_A_DEGREE =
            Pattern.compile("\\bor\\b[^.;\\n]{0,20}$", Pattern.CASE_INSENSITIVE);

    /** A ceiling rather than a floor: "up to 3 years", "at most 2 years". */
    private static final Pattern UPPER_BOUND_BEFORE = Pattern.compile(
            "\\b(?:up\\s*to|upto|maximum(?:\\s+of)?|max\\.?|at\\s+most|less\\s+than"
                    + "|fewer\\s+than|under|no\\s+more\\s+than|not\\s+more\\s+than|within)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A number that caps something rather than requiring it.
     *
     * <p>Two real misses, both from 2026-09-19. HackerRank's L1 role asks for "an
     * early-career engineer with up to 3 years of relevant experience. Strong
     * freshers..." and was rejected as a three-year role. And a privacy footer,
     * "Your data is kept for up to 2 years in our candidate pool", rejected three
     * data and SRE roles in Germany and France as two-year roles.
     */
    private static boolean isUpperBound(String text, Matcher m) {
        String before = text.substring(Math.max(0, m.start() - 25), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        return UPPER_BOUND_BEFORE.matcher(before).find();
    }

    /** A degree, then "or", then the number: "Bachelor's degree, or 4+ years". */
    private static final Pattern DEGREE_THEN_OR = Pattern.compile(
            "\\b(?:degree|diploma)\\b[^.;\\n]{0,40}\\bor\\s+(?:a\\s+)?"
                    + "(?:minimum\\s+(?:of\\s+)?|at\\s+least\\s+)?$",
            Pattern.CASE_INSENSITIVE);

    /** "3 years with a Master's degree": years that come with a degree, not instead of one. */
    private static final Pattern WITH_A_DEGREE_AFTER = Pattern.compile(
            "^\\s+(?:of\\s+\\w+\\s+)?with\\s+(?:a\\s+|an\\s+)?"
                    + "(?:master|ph\\.?d|doctorate|bachelor|m\\.?sc?\\b|b\\.?sc?\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Years offered as a substitute for a degree, which is no bar to someone who
     * has the degree.
     *
     * <p>"Bachelor's degree in Computer Science, or 4+ years of equivalent practical
     * experience" asks for one or the other. Reading the 4 as a requirement rejects
     * an entry-level role. The shape it must not catch is "5 years with a
     * Bachelor's degree, or 3 years with a Master's degree", where each number
     * comes with its own degree and is a real bar.
     */
    private static boolean isDegreeAlternative(String text, Matcher m) {
        String after = text.substring(m.end(), Math.min(text.length(), m.end() + 60));
        if (SUBSTITUTES_AFTER.matcher(after).find()) {
            return true;
        }
        String before = text.substring(Math.max(0, m.start() - 70), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        if (!DEGREE_THEN_OR.matcher(before).find()) {
            return false;
        }
        return !WITH_A_DEGREE_AFTER.matcher(after.substring(0, Math.min(after.length(), 40))).find();
    }

    /** "4 years of relevant experience can substitute for the degree". */
    private static final Pattern SUBSTITUTES_AFTER = Pattern.compile(
            "^[^.;\\n]{0,40}\\b(?:(?:can|may|will)\\s+(?:be\\s+)?substitut\\w*|in\\s+lieu\\s+of)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Whether a number states a qualification, wherever in the document it sits.
     *
     * <p>Anchored on the words around it rather than on a section heading, because
     * the postings this exists for have no requirements heading at all and put the
     * line after the wishlist.
     */
    private static boolean isQualification(String text, Matcher m) {
        // Newlines flattened first: boards render the qualification as a list, so
        // "B.E" and its years sit on separate lines, and an anchor that treated a
        // line break as the end of the clause never fired on a real posting.
        String before = text.substring(Math.max(0, m.start() - 40), m.start())
                .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
        if (ALTERNATIVE_TO_A_DEGREE.matcher(before).find()) {
            return false;
        }
        return QUALIFICATION_BEFORE.matcher(before).find();
    }

    /**
     * Words that state a bar in themselves, right before the number: "Minimum of
     * 5 years", "Experience Minimum 3-4 years", "Years of experience: 3+",
     * "Experience Level 6+ years". Counted wherever they sit, like the
     * qualification line. All four were missed on 2026-10-07 because each sat
     * after a wishlist heading or under a heading this does not know.
     */
    private static final Pattern STATED_BAR_BEFORE = Pattern.compile(
            "\\b(?:minimum(?:\\s+of)?|min\\.|at\\s+least"
                    + "|(?:work\\s+|relevant\\s+|total\\s+)?experience(?:\\s+level)?\\s*:?"
                    + "|years\\s+of\\s+experience\\s*:)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A wishlist word just before, on the same stretch of text: "Nice to have: at
     * least 3 years of Rust" is not a bar. The colon after it is why
     * {@link #isOptional} lets this through.
     */
    private static final Pattern WISH_BEFORE = Pattern.compile(
            "\\b(?:preferred|preferably|ideally|nice[\\s-]to[\\s-]have|good[\\s-]to[\\s-]have|bonus"
                    + "|desirable|desired|a\\s+plus|advantage)\\b[^\\n]{0,50}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * "Bachelor's degree in Computer Science, Engineering, or related field 2-4
     * years of experience" (Fortive, 2026-10-07): the field names the degree's
     * subject, and the years follow it directly. "or related field and 1+ year"
     * is a degree tier and stays out.
     */
    private static final Pattern RELATED_FIELD_BEFORE = Pattern.compile(
            "\\b(?:related|relevant|similar)\\s+(?:field|discipline|area|subject)\\s*[,:]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static Pool statedBars(String description) {
        Pool bars = new Pool();
        Matcher m = YEARS.matcher(description);
        while (m.find()) {
            if (isProgrammeDuration(description, m) || isOptional(description, m)
                    || isDegreeAlternative(description, m) || isUpperBound(description, m)
                    || isCompanyTime(description, m) || isAboutOneTool(description, m)) {
                continue;
            }
            String before = description.substring(Math.max(0, m.start() - 60), m.start())
                    .replaceAll("[\\r\\n\\t\\u00a0]+", " ");
            if (WISH_BEFORE.matcher(before).find()
                    || !(STATED_BAR_BEFORE.matcher(before).find()
                            || RELATED_FIELD_BEFORE.matcher(before).find())) {
                continue;
            }
            int years = Integer.parseInt(m.group(1));
            if (years < IMPLAUSIBLE_YEARS) {
                bars.offer(years, phrase(description, m.start(), m.end()));
            }
        }
        return bars;
    }

    /**
     * A line holding nothing but a range or an "N+": "2-10", "5 to 8", "6+".
     * A lone number is not enough; it could be anything from a floor to a count.
     */
    private static final Pattern BARE_RANGE_LINE = Pattern.compile(
            "(?m)^[ \\t]*(\\d{1,2})[ \\t]*(?:(?:[-–—]|to)[ \\t]*\\d{1,2}[ \\t]*\\+?|\\+)[ \\t]*$",
            Pattern.CASE_INSENSITIVE);

    /** The line above a bare range, when it names a degree: "BE/BTech/ME/MTech". */
    private static final Pattern DEGREE_LINE = Pattern.compile(
            "\\b(?:b\\.?\\s?e|b\\.?\\s?tech|m\\.?\\s?e|m\\.?\\s?tech|b\\.?\\s?sc|m\\.?\\s?sc|mca|bca"
                    + "|bachelor'?s?|master'?s?|degree|graduat\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The last non-blank line before {@code index}. */
    private static String previousLine(String text, int index) {
        String[] lines = text.substring(0, index).split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return "";
    }

    /** A little surrounding text, so the reject reason reads like the posting. */
    private static String phrase(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 40);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
