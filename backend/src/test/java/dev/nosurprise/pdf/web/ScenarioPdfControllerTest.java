package dev.nosurprise.pdf.web;

import dev.nosurprise.pdf.PdfNotReadyException;
import dev.nosurprise.scenario.Scenario;
import dev.nosurprise.scenario.ScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.mongodb.gridfs.GridFsResource;

import com.mongodb.client.gridfs.model.GridFSFile;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScenarioPdfController.class)
@ContextConfiguration(classes = {ScenarioPdfController.class, dev.nosurprise.config.SecurityConfig.class, dev.nosurprise.config.JwtPropertiesConfig.class, dev.nosurprise.common.problem.GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-0123456789abcdef0123456789",
        "app.jwt.access-ttl=15m",
        "app.jwt.refresh-ttl=30d"
})
class ScenarioPdfControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ScenarioService scenarioService;

    @MockitoBean
    GridFsTemplate gridFsTemplate;

    @Test
    @DisplayName("запуск экспорта возвращает 202")
    void exportReturns202() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        when(scenarioService.findOwnedScenario(any(), any())).thenAnswer(inv -> {
            Scenario scenario = org.mockito.Mockito.mock(Scenario.class);
            when(scenario.getId()).thenReturn(scenarioId);
            when(scenario.getUserId()).thenReturn(inv.getArgument(0));
            return scenario;
        });

        mockMvc.perform(post("/api/scenarios/" + scenarioId + "/export/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000000000"))))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("чужой сценарий при экспорте возвращает 404")
    void exportForeignReturns404() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new dev.nosurprise.scenario.ScenarioNotFoundException("not found"))
                .when(scenarioService).requestExport(any(), any());

        mockMvc.perform(post("/api/scenarios/" + scenarioId + "/export/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000000000"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("скачивание готового PDF возвращает PDF и корректные заголовки")
    void downloadReturnsPdf() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Scenario scenario = org.mockito.Mockito.mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenario.getUserId()).thenReturn(userId);
        when(scenarioService.findOwnedScenario(userId, scenarioId)).thenReturn(scenario);

        GridFSFile file = org.mockito.Mockito.mock(GridFSFile.class);
        when(gridFsTemplate.findOne(any())).thenReturn(file);
        GridFsResource resource = org.mockito.Mockito.mock(GridFsResource.class);
        when(resource.exists()).thenReturn(true);
        when(gridFsTemplate.getResource(file)).thenReturn(resource);

        mockMvc.perform(get("/api/scenarios/" + scenarioId + "/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"scenario-" + scenarioId + ".pdf\""));
    }

    @Test
    @DisplayName("PDF ещё не готов возвращает 404 PDF_NOT_READY")
    void downloadNotReady() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Scenario scenario = org.mockito.Mockito.mock(Scenario.class);
        when(scenario.getId()).thenReturn(scenarioId);
        when(scenario.getUserId()).thenReturn(userId);
        when(scenarioService.findOwnedScenario(userId, scenarioId)).thenReturn(scenario);
        when(gridFsTemplate.findOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/scenarios/" + scenarioId + "/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.subject(userId.toString()))))
                .andExpect(status().isNotFound());
    }
}