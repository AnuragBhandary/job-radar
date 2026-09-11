package com.anuragbhandary.jobradar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BenchLlmCommandTest {

    @Test
    @DisplayName("a model name may carry a GPU-layer cap after the tag")
    void parsesModelSpecs() {
        BenchLlmCommand.ModelSpec capped = BenchLlmCommand.ModelSpec.parse(" qwen3.6:27b@45 ");
        BenchLlmCommand.ModelSpec plain = BenchLlmCommand.ModelSpec.parse("qwen3:14b");

        assertThat(capped.name()).isEqualTo("qwen3.6:27b");
        assertThat(capped.asLayers()).isEqualTo(Map.of("qwen3.6:27b", 45));
        assertThat(plain.name()).isEqualTo("qwen3:14b");
        assertThat(plain.asLayers()).isEmpty();
    }

    @Test
    @DisplayName("the defaults are the settings that ran on the target machine")
    void defaultsCarryTheMeasuredCaps() {
        assertThat(BenchLlmCommand.DEFAULT_MODELS)
                .containsExactly("gemma4:26b@24", "qwen3.6:27b@45", "qwen3:14b");
    }
}
