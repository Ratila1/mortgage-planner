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
import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calculator.ScheduleRow;
import dev.nosurprise.offer.BankOffer;
import dev.nosurprise.offer.OfferService;
import dev.nosurprise.scenario.Scenario;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

public class PdfService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'UTC'");

    private final OfferService offerService;

    public PdfService() {
        this(null);
    }

    public PdfService(OfferService offerService) {
        this.offerService = offerService;
    }

    public byte[] generatePdf(CalculationDocument calculation, Scenario scenario) {
        BankOffer offer = null;
        if (offerService != null && scenario != null && scenario.getOfferId() != null) {
            try {
                offer = offerService.findEntity(scenario.getOfferId());
            } catch (RuntimeException ex) {
                offer = null;
            }
        }
        return generatePdf(calculation, scenario, offer);
    }

    public byte[] generatePdf(CalculationDocument calculation, Scenario scenario, BankOffer offer) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writePdf(calculation, scenario, offer, out);
            return out.toByteArray();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate PDF", ex);
        }
    }

    public void writePdf(CalculationDocument calculation, Scenario scenario, OutputStream out) {
        writePdf(calculation, scenario, null, out);
    }

    public void writePdf(CalculationDocument calculation, Scenario scenario, BankOffer offer, OutputStream out) {
        try {
            BaseFont baseFont = BaseFont.createFont("/fonts/Arial.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
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
                    calculation.createdAt().atZone(ZoneOffset.UTC).format(DATE_FORMATTER), normalFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Входные параметры", normalFont));
            document.add(new Paragraph("Цена: " + money(calculation.params().price()), normalFont));
            document.add(new Paragraph("Первоначальный взнос: " + money(calculation.params().downPayment()), normalFont));
            document.add(new Paragraph("Сумма кредита: " + money(calculation.params().loanAmount()), normalFont));
            document.add(new Paragraph("Срок: " + calculation.params().termMonths() + " мес.", normalFont));
            document.add(new Paragraph("Ставка: " + calculation.params().annualRate().toPlainString() + "%", normalFont));
            document.add(new Paragraph("Тип платежа: " + calculation.params().type(), normalFont));
            document.add(new Paragraph("Досрочные погашения: " + earlyRepaymentsText(calculation.params().earlyRepayments()), normalFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Результаты", normalFont));
            document.add(new Paragraph("Ежемесячный платёж: " + money(calculation.summary().monthlyPayment()), normalFont));
            document.add(new Paragraph("Общая сумма выплат: " + money(calculation.summary().totalPaid()), normalFont));
            document.add(new Paragraph("Переплата: " + money(calculation.summary().overpayment()), normalFont));
            if (calculation.savings() != null) {
                document.add(new Paragraph("Экономия от досрочных: " + money(calculation.savings().overpaymentSaved()), normalFont));
            }
            document.add(new Paragraph(" "));

            if (scenario != null) {
                document.add(new Paragraph("Сценарий: " + scenario.getName(), normalFont));
            }
            if (offer != null) {
                document.add(new Paragraph("Предложение банка: " + offer.getBankName() +
                        " (ставка " + offer.getRate().toPlainString() + "%)", normalFont));
            }
            if (scenario != null || offer != null) {
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

            for (ScheduleRow row : safeSchedule(calculation)) {
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

    private static List<ScheduleRow> safeSchedule(CalculationDocument calculation) {
        return Objects.requireNonNullElse(calculation.schedule(), List.of());
    }
}