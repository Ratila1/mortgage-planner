package dev.nosurprise.loan.contract;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PDF договора на OpenPDF (как PDF сценариев). Шрифт DejaVu — встроенные шрифты PDF не умеют кириллицу.
 * Время в договоре — в поясе Europe/Minsk: так его видит клиент.
 */
@Component
public class PdfContractRenderer implements ContractRenderer {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Minsk"));

    @Override
    public byte[] render(ContractData data) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseFont baseFont = BaseFont.createFont("/fonts/DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            Font title = new Font(baseFont, 16, Font.BOLD);
            Font normal = new Font(baseFont, 11);

            Document document = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(document, out);
            document.open();

            Paragraph heading = new Paragraph("Договор микрозайма № " + data.applicationId(), title);
            heading.setAlignment(Element.ALIGN_CENTER);
            document.add(heading);
            document.add(new Paragraph(" "));
            document.add(new Paragraph("Заёмщик: " + fullName(data), normal));
            document.add(new Paragraph("Телефон: " + data.phone(), normal));
            document.add(new Paragraph("Цель займа: " + data.loanTypeName(), normal));
            document.add(new Paragraph(" "));
            document.add(new Paragraph("Сумма займа: " + byn(data.amount()), normal));
            document.add(new Paragraph("Срок: " + data.termDays() + " дн.", normal));
            document.add(new Paragraph("Ставка: " + data.dailyRate().toPlainString() + " % в день", normal));
            document.add(new Paragraph("Проценты за весь срок: " + byn(data.interest()), normal));
            document.add(new Paragraph("Надбавка по типу займа: " + byn(data.modifier()), normal));
            document.add(new Paragraph("Сумма к возврату одним платежом в конце срока: " + byn(data.total()), normal));
            document.add(new Paragraph(" "));
            document.add(new Paragraph("Предложение одобрено: " + format(data.decidedAt()), normal));
            document.add(new Paragraph("Подписать договор нужно до: " + format(data.offerExpiresAt()), normal));
            document.add(new Paragraph(" "));
            document.add(new Paragraph("Договор подписывается простой электронной подписью: "
                    + "кодом из SMS, отправленным на подтверждённый номер телефона заёмщика.", normal));
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to render contract PDF", ex);
        }
    }

    private static String fullName(ContractData data) {
        return data.lastName() + " " + data.firstName() + (data.middleName() == null ? "" : " " + data.middleName());
    }

    private static String byn(BigDecimal value) {
        return value.toPlainString() + " BYN";
    }

    private static String format(Instant instant) {
        return instant == null ? "—" : DATE_TIME.format(instant);
    }
}
