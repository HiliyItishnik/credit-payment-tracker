package dev.portfolio.credittracker.budget;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class ReminderCalendar {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private ReminderCalendar() {}

    static String build(List<RepaymentPlanner.Payment> payments, int days) {
        var calendar = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Payment Tracker//Debt reminders//RU\r\n");
        String stamp = STAMP.format(Instant.now());
        for (var payment : payments) {
            String title = escape("Платёж по долгу «" + payment.name() + "»");
            line(calendar, "BEGIN:VEVENT");
            line(calendar, "UID:" + payment.debtId() + "-" + payment.installment() + "@credit-payment-tracker");
            line(calendar, "DTSTAMP:" + stamp);
            line(calendar, "DTSTART:" + payment.date().format(DateTimeFormatter.BASIC_ISO_DATE) + "T090000");
            line(calendar, "SUMMARY:" + title);
            line(calendar, "DESCRIPTION:" + escape("По графику: " + payment.amount().toPlainString() + " ₽"));
            line(calendar, "BEGIN:VALARM");
            line(calendar, "ACTION:DISPLAY");
            line(calendar, "DESCRIPTION:" + title);
            line(calendar, "TRIGGER:-P" + days + "D");
            line(calendar, "END:VALARM");
            line(calendar, "END:VEVENT");
        }
        line(calendar, "END:VCALENDAR");
        return calendar.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
                .replaceAll("[\\x00-\\x08\\x0B-\\x1F\\x7F]", "");
    }

    private static void line(StringBuilder target, String line) {
        int bytes = 0;
        for (int offset = 0; offset < line.length();) {
            int point = line.codePointAt(offset);
            String character = Character.toString(point);
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > 75) {
                target.append("\r\n ");
                bytes = 1;
            }
            target.append(character);
            bytes += length;
            offset += Character.charCount(point);
        }
        target.append("\r\n");
    }
}
