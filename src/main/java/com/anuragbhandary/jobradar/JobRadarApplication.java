package com.anuragbhandary.jobradar;

import com.anuragbhandary.jobradar.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * job-radar: a scheduled batch job that reads public ATS boards, screens the
 * results against a fixed eligibility profile, and reports what is new.
 *
 * <p>There is no web layer by design. This is a CLI plus a scheduler.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class JobRadarApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
