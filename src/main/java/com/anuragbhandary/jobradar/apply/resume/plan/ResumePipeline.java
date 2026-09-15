package com.anuragbhandary.jobradar.apply.resume.plan;

import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.evidence.EvidenceIntegrityException;
import com.anuragbhandary.jobradar.evidence.EvidenceProperties;
import com.anuragbhandary.jobradar.evidence.EvidenceReadiness;
import org.springframework.stereotype.Component;

/**
 * The resume, and the evidence context, for a posting - as applications use them.
 *
 * <pre>
 *   EvidenceReadiness ─▶ PostingRequirements (deterministic) ─▶ CoverageAnalyzer
 *     ─▶ ledger ─▶ TailoringPlanner ─▶ verified resume + ApplicationEvidenceContext
 * </pre>
 *
 * <p>No language model anywhere on this path. The model-rewrite experiment
 * ({@code bench-rewrite}) is not reachable from here, and a test fails the build if
 * that changes.
 *
 * <p><b>Fails closed.</b> A missing, refused or invalid evidence bank, bullets still
 * in applicant.yml, or a plan that fails verification throws
 * {@link EvidenceIntegrityException}. Nothing falls back to another copy of the
 * applicant's claims, because there is none. {@code job-radar.evidence.tailoring=false}
 * changes only how the canonical claims are selected (tag matching instead of the
 * ledger), never where they come from.
 */
@Component
public class ResumePipeline {

    private final CoverageAnalyzer analyzer;
    private final TailoringPlanner planner;
    private final EvidenceProperties properties;
    private final EvidenceReadiness readiness;

    public ResumePipeline(CoverageAnalyzer analyzer, TailoringPlanner planner,
            EvidenceProperties properties, EvidenceReadiness readiness) {
        this.analyzer = analyzer;
        this.planner = planner;
        this.properties = properties;
        this.readiness = readiness;
    }

    /**
     * @throws EvidenceIntegrityException when the evidence cannot be trusted to
     *                                    generate from
     */
    public TailoringPlan tailor(Posting posting) {
        readiness.requireReady();
        CoverageLedger ledger = analyzer.analyse(posting);
        return properties.tailoringEnabled()
                ? planner.plan(posting, ledger)
                : planner.simple(posting, ledger);
    }
}
