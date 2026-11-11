package dev.nosurprise.pdf.web;

import dev.nosurprise.pdf.PdfNotFoundException;
import dev.nosurprise.pdf.PdfNotReadyException;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioService;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.gridfs.model.GridFSFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioPdfController {

    private final ScenarioService scenarioService;
    private final GridFsTemplate gridFsTemplate;

    public ScenarioPdfController(ScenarioService scenarioService, GridFsTemplate gridFsTemplate) {
        this.scenarioService = scenarioService;
        this.gridFsTemplate = gridFsTemplate;
    }

    @PostMapping("/{id}/export/pdf")
    public ResponseEntity<Void> export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID userId = UUID.fromString(jwt.getSubject());
        scenarioService.requestExport(userId, id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Scenario scenario = scenarioService.findOwnedScenario(userId, id);

        com.mongodb.client.gridfs.model.GridFSFile file = gridFsTemplate.findOne(
                Query.query(Criteria.where("metadata.scenarioId").is(scenario.getId())
                        .and("metadata.userId").is(userId)));

        if (file == null) {
            throw new PdfNotReadyException("pdf not ready for scenario: " + id);
        }

        GridFsResource resource = gridFsTemplate.getResource(file);
        if (!resource.exists()) {
            throw new PdfNotFoundException("pdf not found for scenario: " + id);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"scenario-" + id + ".pdf\"")
                .body(resource);
    }
}