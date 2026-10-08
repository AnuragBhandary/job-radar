package com.anuragbhandary.jobradar.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The kind of tech role a title names, and which resume goes with it.
 *
 * <p>Added 2026-10-08 for three readers: the openings file shows the best few of
 * each category before the rest, the tracker sheet counts applications by
 * category, and each Discord card names it beside the link. Read from the title
 * alone, in the order below, so "Data Platform Engineer" is data engineering
 * before it is platform, and "QA Automation Engineer" is QA before anything.
 * Every tech role lands somewhere; a title naming nothing more specific is
 * software.
 */
public enum RoleCategory {

    /**
     * Not a tech role: Goldman's legal, equity research and sales "New Analyst"
     * programmes he applied to before 2026-10-08. Only the tracker shows it; the
     * radar filters such roles out, so the openings file never does.
     */
    OTHER("Other (non-tech)", Resume.SOFTWARE,
            "\\blegal\\b|equity research|investment research|\\bsales\\b|client solutions"
                    + "|controls governance|\\bcompliance\\b"),
    FULL_STACK("Full-stack / Frontend", Resume.SOFTWARE,
            "full[\\s-]?stack|front[\\s-]?end|\\breact\\b|\\bangular\\b|\\bui (?:engineer|developer)|web developer"),
    QA("QA / Test Automation", Resume.SOFTWARE,
            "\\bqa\\b|quality (?:engineer|assurance|analyst)|software quality|\\btest(?:ing)?\\b|\\bsdet\\b"
                    + "|automation (?:engineer|tester)"),
    DATA_ENGINEERING("Data Engineering", Resume.DATA,
            "data engineer|analytics engineer|\\betl\\b|data platform|data pipeline|pipeline developer"
                    + "|big data|data warehouse|\\bdbt\\b|database (?:engineer|developer)|sql developer"),
    AI_ML("AI / ML", Resume.SOFTWARE,
            "\\bai\\b|\\bml\\b|machine learning|\\bllm|gen\\s?ai|\\bagents?\\b|\\bnlp\\b|computer vision"
                    + "|deep learning|applied scientist"),
    DATA_ANALYTICS("Data Science / Analytics", Resume.DATA,
            "data scien|analytics|analyst|\\bbi\\b|business intelligence|reporting|\\bdata\\b|\\bsql\\b"),
    PLATFORM("Platform / SRE / DevOps", Resume.SOFTWARE,
            "\\bsre\\b|site reliability|devops|platform engineer|infrastructure|\\bcloud\\b|kubernetes|\\bsystems engineer"
                    + "|integration engineer"),
    SOFTWARE("Software / Backend", Resume.SOFTWARE, null);

    /**
     * Which resume a category goes out with. AI/ML uses the software one until
     * the AI/ML projects exist: that variant's numbers are still targets.
     */
    public enum Resume {
        SOFTWARE("Software resume"),
        DATA("Data resume");

        private final String label;

        Resume(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String label;
    private final Resume resume;
    private final Pattern pattern;

    RoleCategory(String label, Resume resume, String regex) {
        this.label = label;
        this.resume = resume;
        this.pattern = regex == null ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public String label() {
        return label;
    }

    public Resume resume() {
        return resume;
    }

    /** The tracker's count table: every category, non-tech last. */
    public static List<RoleCategory> trackerOrder() {
        List<RoleCategory> order = new java.util.ArrayList<>(displayOrder());
        order.add(OTHER);
        return order;
    }

    /** The order the openings file shows categories in. */
    public static List<RoleCategory> displayOrder() {
        return List.of(SOFTWARE, DATA_ENGINEERING, DATA_ANALYTICS, AI_ML, PLATFORM, QA, FULL_STACK);
    }

    /**
     * A title that says software outright. At banks "Analyst" is a level, not a
     * job: Goldman's "Engineering-L2-Analyst-Software Engineering" is software.
     */
    private static final Pattern SAYS_SOFTWARE = Pattern.compile(
            "software|developer|\\bsde\\b|programmer", Pattern.CASE_INSENSITIVE);

    public static RoleCategory of(String title) {
        String t = title == null ? "" : title.replace('_', ' ');
        boolean software = SAYS_SOFTWARE.matcher(t).find();
        for (RoleCategory category : values()) {
            if (category == DATA_ANALYTICS && software) {
                continue;
            }
            if (category.pattern != null && category.pattern.matcher(t).find()) {
                return category;
            }
        }
        return SOFTWARE;
    }

    /** By label, as written in the sheet; null when it is not one of these. */
    public static RoleCategory fromLabel(String label) {
        for (RoleCategory category : values()) {
            if (category.label.equalsIgnoreCase(label == null ? "" : label.strip())) {
                return category;
            }
        }
        return null;
    }
}
