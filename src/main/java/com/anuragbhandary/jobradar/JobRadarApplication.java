package com.anuragbhandary.jobradar;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.config.SchemaMigrator;
import com.anuragbhandary.jobradar.resume.Applicant;
import com.anuragbhandary.jobradar.resume.ResumeSource;
import com.anuragbhandary.jobradar.filter.GeoVocabulary;
import com.anuragbhandary.jobradar.strategy.StrategyProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * job-radar: a daily batch job that reads public ATS boards, screens the results
 * against facts about the applicant, and writes a handoff file for review.
 *
 * <p>There is no web layer, by design. Judging which postings are worth applying
 * to, and what to lead with, happens in a Claude session reading the handoff file;
 * see CLAUDE.md. The form-filling, knowledge and UI code that used to live here is
 * at the tag {@code v1-full}.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        AppProperties.class,
        // Country vocabulary and country strategy. Two roots rather than one
        // because they are different kinds of thing: the vocabulary says where
        // a place is, which is a fact, and the strategy says whether he wants to
        // go there, which is not.
        GeoVocabulary.class,
        StrategyProperties.class,
        // Personal data, imported from ~/.config/job-radar/applicant.yml rather
        // than living in application.yml - see the spring.config.import block.
        ResumeSource.class,
        Applicant.class})
public class JobRadarApplication {

    public static void main(String[] args) {
        // Before Spring, deliberately. Hibernate cannot widen a SQLite CHECK
        // constraint and ddl-auto will not notice one is too narrow, so adding an
        // enum value has twice produced a database that starts fine and fails at
        // the first insert. See SchemaMigrator for why plain JDBC in main is the
        // honest place for this.
        SchemaMigrator.migrate(jdbcUrl());
        new SpringApplicationBuilder(JobRadarApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
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
