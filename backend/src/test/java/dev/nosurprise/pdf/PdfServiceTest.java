package dev.nosurprise.pdf;

import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.calculator.PaymentType;
import dev.nosurprise.scenario.ScenarioExportData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfServiceTest {

    private ScenarioExportData exportData() {
        return new ScenarioExportData(UUID.randomUUID(), UUID.randomUUID(), "Test scenario",
                new LoanParams(new BigDecimal("1000000"), new BigDecimal("100000"), 12, new BigDecimal("12"),
                        PaymentType.ANNUITY, List.of()),
                new LoanSummary(new BigDecimal("80000.00"), new BigDecimal("10000.00"), new BigDecimal("960000.00"), 12),
                List.of(), "calc-1", Instant.now(), null);
    }

    @Test
    @DisplayName("генерирует непустой PDF с корректным заголовком файла")
    void generatesPdf() {
        byte[] pdf = new PdfService().generatePdf(exportData());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("writePdf записывает PDF в OutputStream")
    void writesToStream() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new PdfService().writePdf(exportData(), out);

        assertThat(out.toByteArray()).isNotEmpty();
        assertThat(new String(out.toByteArray(), 0, 5)).startsWith("%PDF-");
    }
}