package com.anuragbhandary.jobradar.knowledge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What he has typed during the preparation happening right now.
 *
 * <p>Highest authority of any source, and the reason is the whole point of
 * having a hierarchy: if he is looking at the form and says "no", a rule written
 * three weeks ago does not get to overrule him. A stored assertion can be stale,
 * mis-scoped or simply wrong; the person at the keyboard is none of those.
 *
 * <p>Held in memory and keyed by posting, so an answer given for one application
 * cannot reach another. Nothing here is persisted: making it durable would make
 * it a scoped assertion, which is what {@code KnowledgeService.remember} is for
 * and is a decision he takes separately.
 *
 * <p>A singleton rather than a request-scoped bean because there is one user and
 * one preparation at a time; the posting id in the key is what keeps two
 * applications apart, not the bean lifecycle.
 */
@Component
public class SessionAnswers {

    private final Map<Long, Map<String, String>> byPosting = new ConcurrentHashMap<>();

    public void record(Long postingId, String conceptId, String value) {
        if (postingId == null || conceptId == null || value == null || value.isBlank()) {
            return;
        }
        byPosting.computeIfAbsent(postingId, key -> new ConcurrentHashMap<>())
                .put(conceptId, value.trim());
    }

    public Optional<String> get(Long postingId, String conceptId) {
        if (postingId == null || conceptId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPosting.getOrDefault(postingId, Map.of()).get(conceptId));
    }

    public Map<String, String> forPosting(Long postingId) {
        return postingId == null ? Map.of()
                : Map.copyOf(byPosting.getOrDefault(postingId, Map.of()));
    }

    /** Called when an application is finished with, so the map does not grow forever. */
    public void clear(Long postingId) {
        if (postingId != null) {
            byPosting.remove(postingId);
        }
    }

    public void clearAll() {
        byPosting.clear();
    }
}
