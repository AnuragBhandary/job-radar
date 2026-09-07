package com.anuragbhandary.jobradar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The CLI context starts with no web layer.
 *
 * <p>Written after a regression that nothing else could catch. Adding Spring
 * Security put a {@code @Configuration} class on the classpath that needed
 * {@code ServerProperties}, which only exists when the web layer is on - and the
 * web layer is deliberately off for every command except {@code ui}. So
 * {@code fetch}, {@code screen}, {@code digest} and {@code sheet-list} all failed
 * to start, while 432 tests stayed green because not one of them booted the
 * context the CLI actually runs in.
 *
 * <p>{@code webEnvironment = NONE} is the point of the test, not an optimisation:
 * it reproduces exactly what {@code main()} selects for those twelve commands.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CliContextTest {

    @Autowired
    private JobRadarCli cli;

    @Test
    @DisplayName("every command bean resolves without a servlet container")
    void contextStartsWithoutTheWebLayer() {
        assertThat(cli).isNotNull();
    }
}
