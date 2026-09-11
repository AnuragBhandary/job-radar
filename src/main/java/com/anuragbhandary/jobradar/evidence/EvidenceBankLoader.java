package com.anuragbhandary.jobradar.evidence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads the evidence file.
 *
 * <p>Plain YAML, read directly rather than through Spring's configuration binding.
 * Spring's relaxed binding canonicalises keys and ignores ones it does not know, and
 * in this file either is dangerous: a misspelt {@code qualifers:} would bind as
 * nothing, and the qualifier it held would silently stop protecting the claim. Here
 * an unknown field refuses the whole file, loudly, and the resume falls back to what
 * it did before there was a bank.
 *
 * <p>Never throws. A missing file is an empty bank; a broken one is an empty bank
 * with the reason in {@link EvidenceBank#problems()}.
 */
public final class EvidenceBankLoader {

    private EvidenceBankLoader() {
    }

    /** The shape of the file. */
    record EvidenceFile(Integer version, List<EvidenceSource> sources, List<EvidenceItem> items) {
    }

    static final int VERSION = 1;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    public static EvidenceBank load(Path path) {
        if (path == null) {
            return EvidenceBank.empty("none",
                    List.of(EvidenceProblem.warning("file", "no evidence file is configured")));
        }
        if (!Files.isRegularFile(path)) {
            return EvidenceBank.empty(path.toString(), List.of(EvidenceProblem.warning("file",
                    "no evidence file at " + path + " - resumes are tailored from the resume "
                            + "alone, as before")));
        }
        try {
            return parse(Files.readString(path), path.toString());
        } catch (IOException e) {
            return EvidenceBank.empty(path.toString(),
                    List.of(EvidenceProblem.error("file", "could not read it: " + e.getMessage())));
        }
    }

    public static EvidenceBank parse(String yaml, String origin) {
        Object tree;
        try {
            tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            return refused(origin, "it is not valid YAML: " + firstLine(e.getMessage()));
        }
        if (tree == null) {
            return refused(origin, "the file is empty");
        }
        if (!(tree instanceof Map)) {
            return refused(origin, "expected a mapping with 'version', 'sources' and 'items'");
        }
        EvidenceFile file;
        try {
            file = MAPPER.convertValue(tree, EvidenceFile.class);
        } catch (IllegalArgumentException e) {
            return refused(origin, describe(e));
        }
        if (file.version() != null && file.version() != VERSION) {
            return refused(origin, "version " + file.version() + " is not one this build reads ("
                    + VERSION + ")");
        }
        EvidenceValidator.Result result = EvidenceValidator.validate(file.sources(), file.items());
        List<EvidenceProblem> problems = new ArrayList<>(result.problems());
        if (result.items().isEmpty() && problems.stream().noneMatch(EvidenceProblem::isError)) {
            problems.add(EvidenceProblem.warning("file", "the file has no items"));
        }
        return new EvidenceBank(origin, result.sources(), result.items(), problems);
    }

    private static EvidenceBank refused(String origin, String reason) {
        return EvidenceBank.empty(origin, List.of(EvidenceProblem.error("file",
                "the whole file was refused: " + reason)));
    }

    /** "unknown field 'qualifers' at items[3]" rather than a Jackson stack of text. */
    private static String describe(IllegalArgumentException e) {
        Throwable cause = e.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return "unknown field '" + unknown.getPropertyName() + "' at " + path(unknown)
                    + " - a misspelt field would silently drop what it holds";
        }
        if (cause instanceof JsonMappingException mapping) {
            return "cannot read " + path(mapping) + ": " + firstLine(mapping.getOriginalMessage());
        }
        return firstLine(e.getMessage());
    }

    private static String path(JsonMappingException e) {
        StringBuilder out = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                out.append('[').append(ref.getIndex()).append(']');
            }
        }
        return out.isEmpty() ? "the top level" : out.toString();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int cut = message.indexOf('\n');
        return (cut < 0 ? message : message.substring(0, cut)).strip();
    }
}
