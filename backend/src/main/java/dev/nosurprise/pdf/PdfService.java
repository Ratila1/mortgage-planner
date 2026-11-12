package dev.nosurprise.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import dev.nosurprise.calculator.ScheduleRow;
import dev.nosurprise.offer.web.dto.OfferResponse;
import dev.nosurprise.scenario.ScenarioExportData;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class PdfService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'UTC'");

    public PdfService() {
    }

    public byte[] generatePdf(ScenarioExportData exportData) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writePdf(exportData, out);
            return out.toByteArray();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate PDF", ex);
        }
    }

    public void writePdf(ScenarioExportData exportData, OutputStream out) {
        try {
            BaseFont baseFont = BaseFont.createFont("/fonts/DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            Font titleFont = new Font(baseFont, 16, Font.BOLD);
            Font normalFont = new Font(baseFont, 11);
            Font smallFont = new Font(baseFont, 9);

            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            Paragraph title = new Paragraph("Расчёт ипотеки", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Дата создания расчёта: " +
                    exportData.createdAt().atZone(ZoneOffset.UTC).format(DATE_FORMATTER), normalFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Входные параметры", normalFont));
            document.add(new Paragraph("Цена: " + money(exportData.params().price()), normalFont));
            document.add(new Paragraph("Первоначальный взнос: " + money(exportData.params().downPayment()), normalFont));
            document.add(new Paragraph("Сумма кредита: " + money(exportData.params().loanAmount()), normalFont));
            document.add(new Paragraph("Срок: " + exportData.params().termMonths() + " мес.", normalFont));
            document.add(new Paragraph("Ставка: " + exportData.params().annualRate().toPlainString() + "%", normalFont));
            document.add(new Paragraph("Тип платежа: " + exportData.params().type(), normalFont));
            document.add(new Paragraph("Досрочные погашения: " + earlyRepaymentsText(exportData.params().earlyRepayments()), normalFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Результаты", normalFont));
            document.add(new Paragraph("Ежемесячный платёж: " + money(exportData.summary().monthlyPayment()), normalFont));
            document.add(new Paragraph("Общая сумма выплат: " + money(exportData.summary().totalPaid()), normalFont));
            document.add(new Paragraph("Переплата: " + money(exportData.summary().overpayment()), normalFont));
            // Savings не входит в read model; при необходимости добавляется отдельно.
            document.add(new Paragraph(" "));

            if (exportData.name() != null) {
                document.add(new Paragraph("Сценарий: " + exportData.name(), normalFont));
            }
            OfferResponse offer = exportData.offer();
            if (offer != null) {
                document.add(new Paragraph("Предложение банка: " + offer.bankName() +
                        " (ставка " + offer.rate() + "%)", normalFont));
            }
            if (exportData.name() != null || offer != null) {
                document.add(new Paragraph(" "));
            }

            document.add(new Paragraph("График платежей", normalFont));
            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            addHeaderCell(table, "Месяц", smallFont);
            addHeaderCell(table, "Платёж", smallFont);
            addHeaderCell(table, "Основной долг", smallFont);
            addHeaderCell(table, "Проценты", smallFont);
            addHeaderCell(table, "Досрочно", smallFont);
            addHeaderCell(table, "Остаток", smallFont);

            for (ScheduleRow row : safeSchedule(exportData)) {
                table.addCell(new Phrase(String.valueOf(row.month()), smallFont));
                table.addCell(new Phrase(money(row.payment()), smallFont));
                table.addCell(new Phrase(money(row.principal()), smallFont));
                table.addCell(new Phrase(money(row.interest()), smallFont));
                table.addCell(new Phrase(money(row.earlyRepayment()), smallFont));
                table.addCell(new Phrase(money(row.balance()), smallFont));
            }
            document.add(table);

            document.close();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate PDF", ex);
        }
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private static String money(Object value) {
        if (value == null) {
            return "0.00";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private static String earlyRepaymentsText(List<?> earlyRepayments) {
        if (earlyRepayments == null || earlyRepayments.isEmpty()) {
            return "нет";
        }
        return earlyRepayments.size() + " шт.";
    }

    private static List<ScheduleRow> safeSchedule(ScenarioExportData exportData) {
        return Objects.requireNonNullElse(exportData.schedule(), List.of());
    }
}