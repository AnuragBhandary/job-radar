package com.anuragbhandary.jobradar.apply.resume.analysis;

/**
 * What kind of thing a requirement is.
 *
 * <p>Descriptive only. Nothing decides what may be claimed from the category -
 * that is {@link com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner}'s
 * job - but it separates "Kafka" from "collaborate cross-functionally" on a
 * screen, and a future planner will treat a soft skill differently from a
 * technology.
 */
public enum RequirementCategory {
    TECHNOLOGY,
    LANGUAGE,
    FRAMEWORK,
    DATABASE,
    CLOUD,
    ARCHITECTURE,
    RESPONSIBILITY,
    DOMAIN,
    SOFT_SKILL,
    OTHER
}
