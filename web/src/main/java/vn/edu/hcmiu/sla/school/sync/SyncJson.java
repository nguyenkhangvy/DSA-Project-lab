package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import vn.edu.hcmiu.sla.core.Text;

/**
 * Reads the agent's JSON into {@link SyncContract} records as strictly as pydantic does on the Python
 * side, and checks every rule. Errors say where and why, but never repeat what was sent.
 */
@Component
public class SyncJson {

    /** A full semester of Blackboard text, JSON-escaped, stays well under this. */
    public static final int MAX_UPLOAD_BYTES = 5_000_000;

    /** The body is bigger than {@link #MAX_UPLOAD_BYTES}. */
    public static class TooLarge extends RuntimeException {
    }

    /** The body isn't valid. Each detail is {"loc": [where…], "msg": why}. */
    public static class Invalid extends RuntimeException {

        private final List<Map<String, Object>> details;

        Invalid(List<Map<String, Object>> details) {
            super("invalid_payload");
            this.details = details;
        }

        public List<Map<String, Object>> getDetails() {
            return details;
        }
    }

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) // keep +07:00 as sent
            .addModule(new SimpleModule().addDeserializer(String.class, new StrippedString()))
            .build();

    private final Validator validator;

    public SyncJson(Validator validator) {
        this.validator = validator;
    }

    /** The request's body, or {@link TooLarge}. */
    public byte[] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            throw new TooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (body.length > MAX_UPLOAD_BYTES) {
            throw new TooLarge();
        }
        return body;
    }

    /** The body as a checked record, or {@link Invalid}. */
    public <T> T read(byte[] body, Class<T> type) {
        T value;
        try {
            value = mapper.readValue(body, type);
        } catch (StreamReadException error) {
            throw new Invalid(List.of(detail(List.of(), "Invalid JSON")));
        } catch (JacksonException error) {
            String message = error instanceof UnrecognizedPropertyException
                    ? "Extra inputs are not permitted"
                    : "Input has the wrong type or format";
            throw new Invalid(List.of(detail(location(error), message)));
        }
        if (value == null) {
            throw new Invalid(List.of(detail(List.of(), "Input should be an object")));
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (ConstraintViolation<T> violation : validator.validate(value)) {
            details.add(detail(location(violation.getPropertyPath()), violation.getMessage()));
        }
        if (!details.isEmpty()) {
            throw new Invalid(details);
        }
        return value;
    }

    private static Map<String, Object> detail(List<Object> loc, String msg) {
        return Map.of("loc", loc, "msg", msg);
    }

    private static List<Object> location(JacksonException error) {
        List<Object> loc = new ArrayList<>();
        for (JacksonException.Reference step : error.getPath()) {
            loc.add(step.getPropertyName() != null ? step.getPropertyName() : step.getIndex());
        }
        return loc;
    }

    private static List<Object> location(Path path) {
        List<Object> loc = new ArrayList<>();
        for (Path.Node node : path) {
            if (node.getIndex() != null) {
                loc.add(node.getIndex());
            }
            if (node.getName() != null) {
                loc.add(node.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT));
            }
        }
        return loc;
    }

    /** Text must be a JSON string, and is trimmed like pydantic's str_strip_whitespace. */
    static class StrippedString extends StdScalarDeserializer<String> {

        StrippedString() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return Text.strip(parser.getString());
        }
    }
}
