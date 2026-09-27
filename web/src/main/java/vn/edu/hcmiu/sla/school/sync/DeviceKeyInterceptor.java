package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;

/**
 * Runs before every sync API request: the laptop must send "Authorization: Bearer &lt;device key&gt;" for
 * an active device, or it gets 401 {"error": "invalid_device_key"}. The device is then available to the
 * controller as the request attribute {@link #DEVICE}.
 */
@Component
public class DeviceKeyInterceptor implements HandlerInterceptor {

    public static final String DEVICE = "syncDevice";

    private final DeviceKeys deviceKeys;

    public DeviceKeyInterceptor(DeviceKeys deviceKeys) {
        this.deviceKeys = deviceKeys;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String header = request.getHeader("Authorization");
        Optional<SchoolSyncDevice> device = header != null && header.startsWith("Bearer ")
                ? deviceKeys.checkIn(header.substring("Bearer ".length()).strip(), LocalDateTime.now(ZoneOffset.UTC))
                : Optional.empty();
        if (device.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"invalid_device_key\"}");
            return false;
        }
        request.setAttribute(DEVICE, device.get());
        return true;
    }
}
