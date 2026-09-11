package com.anuragbhandary.jobradar.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reads the evidence file once, at startup. A missing or broken file never stops the tool. */
@Configuration
public class EvidenceConfig {

    private static final Logger log = LoggerFactory.getLogger(EvidenceConfig.class);

    @Bean
    public EvidenceBank evidenceBank(EvidenceProperties properties) {
        EvidenceBank bank = EvidenceBankLoader.load(properties.file());
        long errors = bank.problems().stream().filter(EvidenceProblem::isError).count();
        if (errors > 0) {
            log.warn("Evidence bank {}: {} problem(s) - run `evidence --check` to see them",
                    bank.origin(), errors);
        } else {
            log.debug("Evidence bank {}: {} item(s)", bank.origin(), bank.items().size());
        }
        return bank;
    }
}
