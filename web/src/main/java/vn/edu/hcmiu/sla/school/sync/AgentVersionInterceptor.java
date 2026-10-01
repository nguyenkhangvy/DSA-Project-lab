package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before the device key check on every sync API request (spec 2026-10-02-agent-exe-design.md, 7): an agent
 * older than {@link #OLDEST} gets 426 {"error": "update_required"}, and updates itself at once instead of failing
 * later on a format it doesn't know. The agent says its version in its User-Agent,
 * "SchoolLifeAssistant/0.2.0 (IU student project)"; a request without one is let through.
 */
@Component
public class AgentVersionInterceptor implements HandlerInterceptor {

    /** The oldest agent still accepted. Raise it when the upload format changes in a way older agents can't follow. */
    static final List<Integer> OLDEST = List.of(0, 1, 0);

    private static final Pattern AGENT = Pattern.compile("SchoolLifeAssistant/(\\d{1,6})\\.(\\d{1,6})(?:\\.(\\d{1,6}))?\\b");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        List<Integer> version = version(request.getHeader("User-Agent"));
        if (version == null || compare(version, OLDEST) >= 0) {
            return true;
        }
        response.setStatus(HttpStatus.UPGRADE_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"update_required\"}");
        return false;
    }

    /** [major, minor, patch] from the User-Agent, or null when it names no agent version. */
    static List<Integer> version(String userAgent) {
        Matcher match = userAgent == null ? null : AGENT.matcher(userAgent);
        if (match == null || !match.find()) {
            return null;
        }
        return List.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)));
    }

    static int compare(List<Integer> a, List<Integer> b) {
        for (int part = 0; part < 3; part++) {
            int difference = Integer.compare(a.get(part), b.get(part));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }
}
