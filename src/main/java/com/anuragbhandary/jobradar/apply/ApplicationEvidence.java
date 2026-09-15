package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.plan.ResumePipeline;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlanner;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.ApplicationEvidenceContext;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
import com.anuragbhandary.jobradar.evidence.EvidenceReadiness;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The evidence context of one application, wherever it is needed.
 *
 * <p>An application is prepared from one selection of evidence: the resume planner
 * makes it, the PDF prints it, and the cover letter and career answers are grounded
 * on it. After the fact - a resumed preparation, a draft regenerated from the
 * preparation screen, the assistant panel - the context is rebuilt from the evidence
 * ids the PDF was printed with, not re-planned, so later prose cannot rest on a
 * different selection from the one already on disk.
 */
@Component
public class ApplicationEvidence {

    private final ResumePipeline pipeline;
    private final TailoringPlanner planner;
    private final CoverageAnalyzer analyzer;
    private final EvidenceReadiness readiness;
    private final EvidenceBank bank;
    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;

    public ApplicationEvidence(ResumePipeline pipeline, TailoringPlanner planner,
            CoverageAnalyzer analyzer, EvidenceReadiness readiness, EvidenceBank bank,
            ApplicationAttemptRepository attempts, PostingRepository postings) {
        this.pipeline = pipeline;
        this.planner = planner;
        this.analyzer = analyzer;
        this.readiness = readiness;
        this.bank = bank;
        this.attempts = attempts;
        this.postings = postings;
    }

    /** A posting's context, from a fresh plan - the same one its resume would be printed from. */
    public ApplicationEvidenceContext forPosting(Posting posting) {
        return pipeline.tailor(posting).evidence();
    }

    /**
     * The context of a resume already printed with these ids.
     *
     * <p>An attempt from before evidence ids were recorded has none; it gets a fresh
     * plan, which is what its PDF was printed from if the bank has not changed since.
     *
     * @throws EvidenceIntegrityException when the bank cannot be used, or no longer
     *                                    holds an item the PDF printed
     */
    public ApplicationEvidenceContext forResume(Posting posting, List<String> printedIds) {
        if (printedIds == null || printedIds.isEmpty()) {
            return forPosting(posting);
        }
        readiness.requireReady();
        return planner.context(posting, analyzer.analyse(posting), printedIds);
    }

    /** The context of an attempt, from the resume it rendered. Empty when the attempt or posting is gone. */
    public Optional<ApplicationEvidenceContext> forAttempt(Long attemptId) {
        if (attemptId == null) {
            return Optional.empty();
        }
        Optional<ApplicationAttempt> attempt = attempts.findById(attemptId);
        if (attempt.isEmpty()) {
            return Optional.empty();
        }
        return postings.findById(attempt.get().getPostingId())
                .map(posting -> forResume(posting, attempt.get().getResumeEvidenceIds()));
    }

    /** What an attempt's cover letter was grounded on, as citations. */
    public List<Evidence> letterEvidence(Long attemptId) {
        List<Evidence> out = new ArrayList<>();
        if (attemptId == null) {
            return out;
        }
        attempts.findById(attemptId).ifPresent(attempt -> attempt.getLetterEvidenceIds()
                .forEach(id -> out.add(Evidence.evidenceItem(id, bank.find(id)
                        .map(item -> shorten(item.claim())).orElse("no longer in the evidence bank")))));
        return out;
    }

    static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 159) + "…";
    }
}
