package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
import com.anuragbhandary.jobradar.pipeline.PipelineService;
import com.anuragbhandary.jobradar.prep.PrepPack;
import com.anuragbhandary.jobradar.prep.PrepService;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * One posting, in full.
 *
 * <p>The page the feed was missing. A row could be prepared or opened on the
 * board's own site, and there was nowhere to read the thing properly: what it
 * asks for, how the score was arrived at, and which of its technologies are
 * absent from the resume. Deciding whether to spend an application on it needed
 * all three and offered none.
 */
@Controller
public class PostingController {

    private final PostingRepository postings;
    private final BoardTokenRepository boards;
    private final MatchScorer scorer;
    private final PrepService prep;
    private final PipelineService pipeline;

    public PostingController(PostingRepository postings, BoardTokenRepository boards,
            MatchScorer scorer, PrepService prep, PipelineService pipeline) {
        this.postings = postings;
        this.boards = boards;
        this.scorer = scorer;
        this.prep = prep;
        this.pipeline = pipeline;
    }

    @GetMapping(value = "/posting/{id}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String posting(@PathVariable Long id) {
        Optional<Posting> found = postings.findById(id);
        if (found.isEmpty()) {
            return Ui.page("Not found", "", "<p class=\"empty\">No posting " + id + ".</p>");
        }
        Posting posting = found.get();
        String company = companyOf(posting);
        MatchScore score = scorer.score(posting);
        PrepPack pack = prep.build(posting, company);
        Optional<JobInterest> interest = pipeline.forPosting(id);

        StringBuilder body = new StringBuilder();
        body.append("<p class=\"crumbs\"><a href=\"/\">feed</a> · ")
                .append(Ui.esc(company)).append("</p>");

        body.append(header(posting, company, score, interest));
        body.append(factors(score));
        body.append(gaps(pack));
        body.append(description(posting));

        String stat = "score <strong>" + score.score() + "</strong>"
                + "<span class=\"sep\">·</span>" + score.band().label();
        return Ui.page(company, stat, body.toString());
    }

    private String header(Posting posting, String company, MatchScore score,
            Optional<JobInterest> interest) {

        String action = interest.isPresent()
                ? "<span class=\"badge badge-soft\">on the board · "
                        + Ui.esc(interest.get().getStage().label()) + "</span>"
                : """
                  <form method="post" action="/save">
                    <input type="hidden" name="postingId" value="%d">
                    <button class="btn btn-sm" type="submit">Save</button>
                  </form>
                  """.formatted(posting.getId());

        return """
                <section class="card attempt-head">
                  <div class="head-top">
                    %s
                    <h1>%s</h1>
                  </div>
                  <span class="role">%s</span>
                  <div class="meta">
                    %s<span>%s</span><span class="dot">·</span>
                    <span>stated pay: %s</span><span class="dot">·</span>
                    <span>sponsorship: %s</span><span class="dot">·</span>
                    <span>experience asked: %s</span>
                  </div>
                  <div class="meta">
                    <a href="%s" target="_blank" rel="noreferrer">open the posting</a>
                    %s
                    <form method="post" action="/prepare" class="prepare-form">
                      <input type="hidden" name="postingId" value="%d">
                      <button class="btn btn-primary btn-sm" type="submit">Prepare</button>
                    </form>
                  </div>
                </section>
                """.formatted(
                        Ui.scoreCell(score),
                        Ui.esc(company),
                        Ui.esc(posting.getTitle()),
                        posting.getCountry() == null ? ""
                                : Ui.badge("country", posting.getCountry().name()),
                        Ui.esc(nullSafe(posting.getLocation())),
                        Ui.esc(orNone(posting.getSalaryText())),
                        Ui.esc(orNone(posting.getSponsorshipSignal())),
                        Ui.esc(pack(posting)),
                        Ui.esc(nullSafe(posting.getUrl())),
                        action,
                        posting.getId());
    }

    /** The score, broken into its five factors with a bar each. */
    private String factors(MatchScore score) {
        StringBuilder bars = new StringBuilder("<div class=\"factors\">");
        for (MatchScore.Factor factor : score.factors()) {
            bars.append("""
                    <div class="factor score-%s">
                      <span class="name">%s</span>
                      <span class="bar"><i style="width:%d%%"></i></span>
                      <span class="pts">%d/%d</span>
                      <span class="detail">%s</span>
                    </div>
                    """.formatted(factor.band().label(), Ui.esc(factor.label()),
                            factor.percent(), factor.points(), factor.max(),
                            Ui.esc(factor.detail())));
        }
        bars.append("</div>");

        return "<section class=\"card panel\">"
                + Ui.panelHead("Why " + score.score(),
                        Ui.noteMuted("arithmetic, not judgement"))
                + "<div class=\"panel-body\">" + bars + "</div></section>";
    }

    /**
     * What the posting names that the resume does not.
     *
     * <p>The half of a match worth acting on. Everything it has in common with the
     * resume is already why it is here; the gap list is what to read before an
     * interview and what to be honest about in one.
     */
    private String gaps(PrepPack pack) {
        StringBuilder body = new StringBuilder("<div class=\"panel-body\">");
        if (pack.gaps().isEmpty()) {
            body.append("<p class=\"empty\">Nothing. Every technology this posting "
                    + "names is already on your resume.</p>");
        } else {
            body.append("<div class=\"chips\">");
            pack.gaps().forEach(gap -> body.append("<span class=\"tag\">")
                    .append(Ui.esc(gap)).append("</span>"));
            body.append("</div><p class=\"note-muted\">An honest \"I have not used it, "
                    + "here is the nearest thing I have done\" beats a guess.</p>");
        }
        if (!pack.covered().isEmpty()) {
            body.append("<p class=\"note-muted\" style=\"margin-top:10px\">You already have: ")
                    .append(Ui.esc(String.join(", ", pack.covered()))).append("</p>");
        }
        body.append("</div>");

        return "<section class=\"card panel\">"
                + Ui.panelHead("Revise these",
                        Ui.noteMuted(pack.gaps().size() + " of "
                                + (pack.gaps().size() + pack.covered().size()) + " named"))
                + body + "</section>";
    }

    private String description(Posting posting) {
        if (posting.getDescriptionText() == null || posting.getDescriptionText().isBlank()) {
            return "";
        }
        return "<section class=\"card panel\">"
                + Ui.panelHead("The posting", null)
                + "<div class=\"panel-body letter\"><p>"
                + Ui.esc(posting.getDescriptionText()) + "</p></div></section>";
    }

    private static String pack(Posting posting) {
        Integer years = posting.getMinYears();
        if (years == null) {
            return "not screened";
        }
        return years < 0 ? "none stated" : years + "+ years";
    }

    private String companyOf(Posting posting) {
        return boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken())
                .map(board -> board.getLabel() == null ? board.getToken() : board.getLabel())
                .orElse(posting.getBoardToken());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }
}
