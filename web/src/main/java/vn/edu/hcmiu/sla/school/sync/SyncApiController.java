package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.StartRun;

/**
 * The three addresses the laptop agent calls (JSON; the device key instead of a login):
 * "is a sync due?", "I'm starting a sync", and "here is the data, or what went wrong".
 */
@RestController
@RequestMapping("/api/school/sync")
public class SyncApiController {

    private final SyncJson json;
    private final SyncRuns syncRuns;
    private final SchoolSyncRunRepository runs;
    private final Ingest ingest;

    public SyncApiController(SyncJson json, SyncRuns syncRuns, SchoolSyncRunRepository runs, Ingest ingest) {
        this.json = json;
        this.syncRuns = syncRuns;
        this.runs = runs;
        this.ingest = ingest;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    @GetMapping("/check")
    Map<String, Object> check(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device) {
        SyncRuns.Check check = syncRuns.check(device.getUserId(), now());
        return Map.of(
                "due", check.decision().due(),
                "reason", check.decision().reason(),
                "interval_hours", check.settings().getIntervalHours());
    }

    @PostMapping("/runs")
    ResponseEntity<Map<String, Object>> start(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device,
            HttpServletRequest request) throws IOException {
        StartRun body = json.read(json.body(request), StartRun.class);
        SchoolSyncRun run = syncRuns.start(device, body.trigger(), now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("run_id", run.getId()));
    }

    @PostMapping("/runs/{runId}/finish")
    ResponseEntity<Map<String, Object>> finish(@PathVariable int runId,
            @RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device, HttpServletRequest request)
            throws IOException {
        SchoolSyncRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.getUserId().equals(device.getUserId())) {
            return error(HttpStatus.NOT_FOUND, "not_found");
        }
        if (!run.getStatus().equals(SchoolSyncRun.RUNNING)) {
            return error(HttpStatus.CONFLICT, "run_not_running");
        }
        FinishRun body = json.read(json.body(request), FinishRun.class);
        String status = ingest.finishRun(run.getId(), body, now());
        return ResponseEntity.ok(Map.of("status", status));
    }

    @ExceptionHandler(SyncRuns.RunInProgress.class)
    ResponseEntity<Map<String, Object>> runInProgress() {
        return error(HttpStatus.CONFLICT, "run_in_progress");
    }

    @ExceptionHandler(SyncJson.TooLarge.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large");
    }

    @ExceptionHandler(SyncJson.Invalid.class)
    ResponseEntity<Map<String, Object>> invalid(SyncJson.Invalid invalid) {
        return ResponseEntity.unprocessableContent()
                .body(Map.of("error", "invalid_payload", "details", invalid.getDetails()));
    }
}
