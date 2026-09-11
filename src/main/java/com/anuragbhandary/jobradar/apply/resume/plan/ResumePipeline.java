package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.EvidenceProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The resume for a posting, as applications use it.
 *
 * <pre>
 *   posting ─▶ PostingRequirements (deterministic) ─▶ CoverageAnalyzer ─▶ ledger
 *           ─▶ TailoringPlanner ─▶ evidence-selected resume, verified
 * </pre>
 *
 * <p>No language model anywhere on this path. Requirements are read by the
 * deterministic reader, the ledger by the positioner, the plan by selection. The
 * model-rewrite experiment ({@code bench-rewrite}) is not reachable from here.
 *
 * <p>Fails safe, in three ways. With {@code job-radar.evidence.tailoring} off the
 * ledger is not even built. When the ledger or the planner throws, the posting gets
 * the resume it would have got before the bank existed and the reason is logged.
 * And the planner itself steps aside when the bank is missing or out of step with
 * the resume.
 */
@Component
public class ResumePipeline {

    private static final Logger log = LoggerFactory.getLogger(ResumePipeline.class);

    private final ResumeTailor tailor;
    private final CoverageAnalyzer analyzer;
    private final TailoringPlanner planner;
    private final EvidenceProperties properties;

    public ResumePipeline(ResumeTailor tailor, CoverageAnalyzer analyzer, TailoringPlanner planner,
            EvidenceProperties properties) {
        this.tailor = tailor;
        this.analyzer = analyzer;
        this.planner = planner;
        this.properties = properties;
    }

    public TailoringPlan tailor(Posting posting) {
        if (!properties.tailoringEnabled()) {
            return TailoringPlan.legacy(tailor.tailor(posting), null,
                    List.of("evidence tailoring is switched off (job-radar.evidence.tailoring)"));
        }
        CoverageLedger ledger;
        try {
            ledger = analyzer.analyse(posting);
        } catch (RuntimeException e) {
            log.warn("Coverage ledger failed for posting {}: {}", posting.getId(), e.toString());
            return TailoringPlan.legacy(tailor.tailor(posting), null,
                    List.of("the coverage ledger failed: " + e.getMessage()));
        }
        try {
            return planner.plan(posting, ledger);
        } catch (RuntimeException e) {
            log.warn("Evidence planner failed for posting {}: {}", posting.getId(), e.toString());
            return TailoringPlan.legacy(tailor.tailor(posting), ledger,
                    List.of("the evidence planner failed: " + e.getMessage()));
        }
    }
}
