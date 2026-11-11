package dev.nosurprise.pdf;

import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.LoanSummary;
import dev.nosurprise.calculator.PaymentType;
import dev.nosurprise.scenario.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfServiceTest {

    @Test
    @DisplayName("генерирует непустой PDF с корректным заголовком файла")
    void generatesPdf() {
        LoanParams params = new LoanParams(new BigDecimal("1000000"), new BigDecimal("100000"),
                12, new BigDecimal("12"), PaymentType.ANNUITY, List.of());
        LoanSummary summary = new LoanSummary(new BigDecimal("80000.00"), new BigDecimal("10000.00"),
                new BigDecimal("960000.00"), 12);
        CalculationDocument calc = new CalculationDocument("calc-1", "hash", null, params, summary, null,
                List.of(), Instant.now(), null);
        Scenario scenario = new Scenario(UUID.randomUUID(), "Test scenario", "{}", "{}", "calc-1", null);

        byte[] pdf = new PdfService().generatePdf(calc, scenario);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("writePdf записывает PDF в OutputStream")
    void writesToStream() throws Exception {
        LoanParams params = new LoanParams(new BigDecimal("1000000"), new BigDecimal("100000"),
                12, new BigDecimal("12"), PaymentType.ANNUITY, List.of());
        LoanSummary summary = new LoanSummary(new BigDecimal("80000.00"), new BigDecimal("10000.00"),
                new BigDecimal("960000.00"), 12);
        CalculationDocument calc = new CalculationDocument("calc-1", "hash", null, params, summary, null,
                List.of(), Instant.now(), null);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new PdfService().writePdf(calc, null, out);

        assertThat(out.toByteArray()).isNotEmpty();
        assertThat(new String(out.toByteArray(), 0, 5)).startsWith("%PDF-");
    }
}