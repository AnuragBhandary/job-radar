package com.anuragbhandary.jobradar.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the applying machinery keeps its things.
 *
 * @param outputDir      one directory per attempt: the tailored PDF, the letter,
 *                       the screenshot and the review file
 * @param browserProfile Chromium's user-data directory. Holds live session
 *                       cookies for every board signed into, so it lives outside
 *                       the repository by default and must stay there.
 * @param headless       false by default. The design assumes a human is watching
 *                       at the point of submission, and a headed browser is also
 *                       the only practical way to finish an application the tool
 *                       has correctly refused to finish itself.
 * @param slowMoMs       a pause between browser actions. Not evasion - forms with
 *                       React-controlled inputs drop values typed faster than
 *                       their onChange handlers run, and the failure looks like a
 *                       field that filled and then emptied itself.
 */
@ConfigurationProperties(prefix = "job-radar.apply")
public record ApplyProperties(
        String outputDir,
        String browserProfile,
        boolean headless,
        int slowMoMs,
        int pageTimeoutSeconds) {
}
