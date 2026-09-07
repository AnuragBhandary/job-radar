package com.anuragbhandary.jobradar;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.ApplyProperties;
import com.anuragbhandary.jobradar.apply.llm.LlmProperties;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.config.SchemaMigrator;
import com.anuragbhandary.jobradar.mail.GmailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * job-radar: a scheduled batch job that reads public ATS boards, screens the
 * results against a fixed eligibility profile, and reports what is new.
 *
 * <p>There is no web layer by design. This is a CLI plus a scheduler.
 *
 * <p>Since milestone 9 it also prepares applications: it tailors the resume to a
 * posting, renders it, drafts a cover letter where the form has a box for one,
 * fills the form in a real browser and stops in front of the submit button. See
 * {@link com.anuragbhandary.jobradar.apply.ApplyService} for why it stops there.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        AppProperties.class,
        // The applying side. ApplicantProfile and ResumeModel are personal data
        // and are imported from ~/.config/job-radar/ rather than living in
        // application.yml - see the spring.config.import block there.
        ApplicantProfile.class,
        ResumeModel.class,
        ApplyProperties.class,
        LlmProperties.class,
        GmailProperties.class})
public class JobRadarApplication {

    public static void main(String[] args) {
        // Before Spring, deliberately. Hibernate cannot widen a SQLite CHECK
        // constraint and ddl-auto will not notice one is too narrow, so adding an
        // enum value has twice produced a database that starts fine and fails at
        // the first insert. See SchemaMigrator for why plain JDBC in main is the
        // honest place for this.
        SchemaMigrator.migrate(jdbcUrl());
        SpringApplication.run(JobRadarApplication.class, args);
    }

    /**
     * The same URL {@code application.yml} resolves, read the same way.
     *
     * <p>Duplicated rather than injected because this runs before the Spring
     * environment exists. The default is repeated in exactly one other place and
     * the two must agree; a mismatch migrates a database nobody is using.
     */
    private static String jdbcUrl() {
        String configured = System.getenv("JOB_RADAR_DB");
        return configured != null && !configured.isBlank()
                ? configured : "jdbc:sqlite:./job-radar.db";
    }
}
