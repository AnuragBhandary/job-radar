package com.anuragbhandary.jobradar.apply;

/**
 * Where {@link ApplyService} says what it is doing.
 *
 * <p>An interface rather than a direct write to the attempt row so that the CLI,
 * which prints to a terminal and has no reason to touch the database on every
 * step, and the web runner, which does, can both watch the same run. It also
 * keeps {@link ApplyService} free of any opinion about how progress is shown.
 *
 * <p>Implementations must not throw. A reporter that fails is a progress bar that
 * failed, and losing a real application to it would be absurd - {@link ApplyService}
 * guards against it, and this is the note saying why that guard is there.
 */
@FunctionalInterface
public interface StageReporter {

    /** The reporter that does nothing. What the CLI and the tests use. */
    StageReporter NONE = (stage, detail) -> { };

    /**
     * @param detail one short line about this particular run - the board being
     *               opened, the number of fields read. Never a field value: this
     *               is written to a row that is rendered into a page.
     */
    void at(PreparationStage stage, String detail);
}
