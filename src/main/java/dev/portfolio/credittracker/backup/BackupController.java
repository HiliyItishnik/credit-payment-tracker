package dev.portfolio.credittracker.backup;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/backup")
public class BackupController {
    private final BackupService service;
    public BackupController(BackupService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<BackupService.Backup> export() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=credit-tracker-" + LocalDate.now() + ".json")
                .body(service.export());
    }

    @PostMapping("/preview")
    public BackupService.Preview preview(@RequestBody BackupService.Backup backup) { return service.preview(backup); }

    @PostMapping("/restore")
    public Map<String, Integer> restore(@RequestBody BackupService.Restore request) { return service.restore(request); }
}
