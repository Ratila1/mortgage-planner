package dev.nosurprise.mortgage.pdf;

import dev.nosurprise.mortgage.auth.User;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final PdfExportService pdfExportService;

    public PdfController(PdfExportService pdfExportService) {
        this.pdfExportService = pdfExportService;
    }

    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PdfExportResponse> exportPdf(@AuthenticationPrincipal User user,
                                              @RequestBody ExportRequest request) {
        return pdfExportService.createExportTask(String.valueOf(user.id()), request.scenarioId())
                .map(task -> new PdfExportResponse(task.id()));
    }

    @GetMapping(value = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PdfExportTask> getExportStatus(@AuthenticationPrincipal User user, @PathVariable String taskId) {
        return pdfExportService.getTaskStatus(taskId)
                .filter(task -> task.userId().equals(String.valueOf(user.id())))
                .switchIfEmpty(Mono.error(new PdfExportServiceImpl.TaskNotFoundException("Task not found")));
    }

    public record ExportRequest(String scenarioId) {}
    public record PdfExportResponse(String taskId) {}
}







