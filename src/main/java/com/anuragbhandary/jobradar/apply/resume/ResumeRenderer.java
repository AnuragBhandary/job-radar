package com.anuragbhandary.jobradar.apply.resume;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link TailoredResume} to print-ready HTML.
 *
 * <p>HTML rather than LaTeX or docx, because the browser this project already
 * carries for filling forms will also print it, and a second document toolchain
 * is a second thing to install and keep working on a machine that has to produce
 * a resume at 2am.
 *
 * <p>The CSS is written for paper, not for a screen: physical units, an explicit
 * {@code @page} size, and {@code break-inside: avoid} on every block that would
 * read badly split across a page boundary. Nothing here is responsive, because
 * the only viewport that matters is A4.
 *
 * <p><strong>Every value is escaped.</strong> The bullets come from a YAML file
 * the applicant writes, so this is not a security boundary - but an ampersand in
 * "R&D" produces an invalid entity, and the failure is a resume that renders with
 * a chunk missing rather than an error.
 */
@Component
public class ResumeRenderer {

    private final ApplicantProfile profile;

    public ResumeRenderer(ApplicantProfile profile) {
        this.profile = profile;
    }

    public String toHtml(TailoredResume resume, String headline) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(profile.name().full())).append("</title>")
                .append("<style>").append(css()).append("</style></head><body>");

        header(html, headline);

        section(html, "Summary");
        html.append("<p class=\"summary\">").append(escape(resume.summary().text())).append("</p>");

        if (notEmpty(resume.skills())) {
            section(html, "Technical Skills");
            html.append("<table class=\"skills\">");
            for (ResumeModel.SkillGroup group : resume.skills()) {
                html.append("<tr><td class=\"sk-label\">").append(escape(group.group()))
                        .append("</td><td class=\"sk-items\">")
                        .append(escape(String.join(", ", group.items())))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }

        if (notEmpty(resume.experience())) {
            section(html, "Experience");
            for (ResumeModel.Job job : resume.experience()) {
                html.append("<div class=\"entry\">");
                html.append("<div class=\"row\"><span class=\"role\">")
                        .append(escape(job.title())).append(" · ")
                        .append(escape(job.company()))
                        .append("</span><span class=\"period\">")
                        .append(escape(job.period())).append("</span></div>");

                // The arrangement, printed. See ResumeModel for why this line is
                // stated rather than left for a background check to discover.
                StringJoiner sub = new StringJoiner(" · ");
                addIf(sub, job.note());
                addIf(sub, job.location());
                if (sub.length() > 0) {
                    html.append("<div class=\"sub\">").append(escape(sub.toString()))
                            .append("</div>");
                }
                bullets(html, job.bullets());
                html.append("</div>");
            }
        }

        if (notEmpty(resume.projects())) {
            section(html, "Projects");
            for (ResumeModel.Project project : resume.projects()) {
                html.append("<div class=\"entry\">")
                        .append("<div class=\"role\">").append(escape(project.name()))
                        .append("</div>")
                        .append("<div class=\"sub\">").append(escape(project.stack()))
                        .append("</div>");
                bullets(html, project.bullets());
                html.append("</div>");
            }
        }

        if (notEmpty(resume.education())) {
            section(html, "Education");
            for (ResumeModel.Education degree : resume.education()) {
                html.append("<div class=\"entry\">")
                        .append("<div class=\"row\"><span class=\"role\">")
                        .append(escape(degree.degree())).append(" — ")
                        .append(escape(degree.institution()))
                        .append("</span><span class=\"period\">")
                        .append(escape(degree.period())).append("</span></div>");
                StringJoiner sub = new StringJoiner(" · ");
                addIf(sub, degree.location());
                addIf(sub, degree.detail());
                if (sub.length() > 0) {
                    html.append("<div class=\"sub\">").append(escape(sub.toString()))
                            .append("</div>");
                }
                html.append("</div>");
            }
        }

        if (notEmpty(resume.extras())) {
            section(html, "Additional");
            html.append("<ul>");
            for (String extra : resume.extras()) {
                html.append("<li>").append(escape(extra)).append("</li>");
            }
            html.append("</ul>");
        }

        return html.append("</body></html>").toString();
    }

    private void header(StringBuilder html, String headline) {
        ApplicantProfile.Contact contact = profile.contact();
        html.append("<h1>").append(escape(profile.name().full().toUpperCase())).append("</h1>");
        if (headline != null && !headline.isBlank()) {
            html.append("<div class=\"headline\">").append(escape(headline)).append("</div>");
        }

        StringJoiner line = new StringJoiner(" · ");
        addIf(line, contact.phoneE164());
        addIf(line, contact.email());
        addIf(line, strip(contact.portfolio()));
        addIf(line, strip(contact.linkedin()));
        addIf(line, strip(contact.github()));
        // City and country only. A street address on a resume is data an
        // employer does not need and a job board does not protect.
        addIf(line, profile.address().city() + ", " + profile.address().country());
        html.append("<div class=\"contact\">").append(escape(line.toString())).append("</div>");
    }

    private static void bullets(StringBuilder html, List<ResumeModel.Bullet> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return;
        }
        html.append("<ul>");
        for (ResumeModel.Bullet bullet : bullets) {
            html.append("<li>").append(escape(bullet.text())).append("</li>");
        }
        html.append("</ul>");
    }

    private static void section(StringBuilder html, String title) {
        html.append("<h2>").append(escape(title)).append("</h2>");
    }

    private static void addIf(StringJoiner joiner, String value) {
        if (value != null && !value.isBlank()) {
            joiner.add(value.trim());
        }
    }

    /** "https://linkedin.com/in/x" reads better on paper as "linkedin.com/in/x". */
    private static String strip(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Print CSS.
     *
     * <p>{@code @page} margins are set here rather than in the PDF call because
     * Chromium honours the stylesheet and ignores its own margin option when both
     * are given - so having them in two places means one of them is a lie.
     */
    private static String css() {
        return """
                @page { size: A4; margin: 11mm 12mm; }
                * { box-sizing: border-box; }
                body {
                  font-family: "Charter", "Georgia", "Times New Roman", serif;
                  font-size: 9.2pt; line-height: 1.27; color: #111; margin: 0;
                  -webkit-print-color-adjust: exact;
                }
                h1 {
                  font-size: 15pt; letter-spacing: 0.06em; margin: 0 0 1pt;
                  font-weight: 600; text-align: center;
                }
                .headline {
                  text-align: center; font-size: 9.6pt; color: #333;
                  letter-spacing: 0.04em; margin-bottom: 2pt;
                }
                .contact {
                  text-align: center; font-size: 8.4pt; color: #333;
                  margin-bottom: 5pt;
                }
                h2 {
                  font-size: 9pt; text-transform: uppercase; letter-spacing: 0.09em;
                  border-bottom: 0.6pt solid #999; padding-bottom: 1.2pt;
                  margin: 7pt 0 3pt; font-weight: 600;
                }
                .summary { margin: 0 0 2pt; text-align: justify; }
                /* Keep an entry and its bullets on one page. A job title stranded
                   at the foot of page one is the single most common way a
                   generated resume looks generated. */
                .entry { margin-bottom: 4pt; break-inside: avoid; page-break-inside: avoid; }
                .row { display: flex; justify-content: space-between; gap: 8pt; }
                .role { font-weight: 600; }
                .period { white-space: nowrap; color: #333; font-size: 8.8pt; }
                .sub { font-style: italic; color: #444; font-size: 8.8pt; margin-bottom: 1pt; }
                ul { margin: 1.5pt 0 0; padding-left: 11pt; }
                li { margin-bottom: 1pt; text-align: justify; }
                table.skills { border-collapse: collapse; width: 100%; }
                table.skills td { vertical-align: top; padding: 0.6pt 0; }
                /* The gap is on the VALUE cell, not as padding on the label.
                   `* { box-sizing: border-box }` above makes padding count inside
                   a shrink-to-fit `width: 1%` cell, so padding-right there is
                   absorbed and the longest label - "Cloud & DevOps" - ends up
                   touching its value with no space at all. */
                .sk-label { font-weight: 600; white-space: nowrap; width: 1%; }
                /* Qualified as `table.skills td.sk-items`, not `.sk-items`. The
                   `table.skills td` rule above is (0,1,2) on specificity and a
                   bare class is (0,1,0), so it wins and the padding never
                   applies - which is exactly what happened, and it looked like
                   the padding was being absorbed by box-sizing rather than
                   losing a cascade. */
                table.skills td.sk-items { padding-left: 9pt; }
                """;
    }
}
