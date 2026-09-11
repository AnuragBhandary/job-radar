package com.anuragbhandary.jobradar.bench;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The benchmark's view of a model server.
 *
 * <p>An interface so the harness can be tested with a model that fails, returns
 * garbage or invents quotes on demand - which is most of what the harness has to
 * handle correctly.
 */
public interface LocalModel {

    /**
     * @param format a JSON schema the reply must follow, or null for free text
     */
    ModelCall chat(String model, String system, String user, Map<String, Object> format);

    /** Models the server has, or empty when it cannot say. */
    default List<String> installed() {
        return List.of();
    }

    /** How much memory the loaded model occupies, if the server reports it. */
    default Optional<Residency> residency(String model) {
        return Optional.empty();
    }

    /** Frees the model's memory before the next one loads. */
    default void unload(String model) {
    }

    /**
     * @param sizeBytes total memory the loaded model occupies
     * @param vramBytes the part of it the GPU holds. Less than {@code sizeBytes}
     *                  means some layers run on the CPU, which is the usual reason
     *                  a model is slower than its size suggests.
     */
    record Residency(long sizeBytes, long vramBytes) {
    }
}
