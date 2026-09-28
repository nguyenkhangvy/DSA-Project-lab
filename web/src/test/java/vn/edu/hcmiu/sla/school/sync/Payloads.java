package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uploads for tests, from contract/samples/ (the Python tests use the same files), as plain maps and
 * lists that a test can change before sending.
 */
final class Payloads {

    static final Path SAMPLES = Path.of("..", "contract", "samples");
    static final String BB = "https://blackboard.hcmiu.edu.vn";

    /** Writes JSON like the agent's Python does: letters with diacritics as \\uXXXX. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private Payloads() {
    }

    /** A complete, valid upload from the agent. Times are Vietnam time (+07:00). */
    static Map<String, Object> fullPayload() {
        return read("finish-edusoft.json");
    }

    /** A valid Blackboard section's data, as the agent uploads it. */
    static Map<String, Object> blackboardPayload() {
        return at(read("finish-blackboard.json"), "blackboard", "data");
    }

    /** A valid Outlook section's data, as the agent uploads it: results only, never an email's text. */
    static Map<String, Object> outlookPayload() {
        return at(read("finish-outlook.json"), "outlook", "data");
    }

    static Map<String, Object> ok(Object data) {
        return Map.of("status", "ok", "data", data);
    }

    static Map<String, Object> failed(String errorCode, String message) {
        return Map.of("status", "failed", "error_code", errorCode, "error_message", message);
    }

    /** The map inside {@code root} at a path of keys and list positions, e.g. at(p, "timetable", "data"). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> at(Object root, Object... path) {
        Object here = root;
        for (Object step : path) {
            here = step instanceof Integer index ? ((List<Object>) here).get(index) : ((Map<String, Object>) here).get(step);
        }
        return (Map<String, Object>) here;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object root, Object... path) {
        Map<String, Object> parent = at(root, java.util.Arrays.copyOf(path, path.length - 1));
        return (List<Object>) parent.get(path[path.length - 1]);
    }

    static byte[] bytes(Object payload) {
        return JSON.writeValueAsBytes(payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String name) {
        try {
            return JSON.readValue(Files.readAllBytes(SAMPLES.resolve(name)), Map.class);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
