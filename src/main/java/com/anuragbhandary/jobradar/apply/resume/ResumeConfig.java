package com.anuragbhandary.jobradar.apply.resume;

import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The resume every component sees: applicant.yml's presentation joined with the
 * evidence bank's claims, once, at startup.
 *
 * <p>Never fails the boot. A problem joining the two is recorded on
 * {@link ComposedResume} and stops application generation through
 * {@link com.anuragbhandary.jobradar.evidence.EvidenceReadiness}; every command
 * that does not generate an application - fetch, screen, digest - runs regardless.
 */
@Configuration
public class ResumeConfig {

    private static final Logger log = LoggerFactory.getLogger(ResumeConfig.class);

    @Bean
    public ComposedResume composedResume(ResumeProfile profile, EvidenceBank bank) {
        ComposedResume composed = ResumeComposer.compose(profile, bank);
        if (!composed.blocking().isEmpty()) {
            log.warn("The resume could not be composed cleanly from applicant.yml and the "
                    + "evidence bank ({} problem(s)) - run `evidence --check`", composed.blocking().size());
        }
        return composed;
    }

    @Bean
    public ResumeModel resumeModel(ComposedResume composed) {
        return composed.resume();
    }
}
