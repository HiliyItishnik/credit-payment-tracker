package dev.portfolio.credittracker.backup;

import dev.portfolio.credittracker.api.ApiException;
import dev.portfolio.credittracker.domain.CalculationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class BackupService {
    public record Backup(int formatVersion, Instant createdAt, Map<String, List<Map<String, Object>>> data, String checksum) {}
    public record Preview(Instant createdAt, Map<String, Integer> imported, Map<String, Integer> current, String currentChecksum) {}
    public record Restore(Backup backup, String currentChecksum) {}

    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("loans", "id");
        TABLES.put("payments", "loan_id, installment_number");
        TABLES.put("monthly_budget", "id");
        TABLES.put("budget_actuals", "month");
        TABLES.put("budget_calendar_marks", "month, entry_id");
        TABLES.put("budget_calendar_dates", "month, entry_id");
        TABLES.put("budget_calendar_amounts", "month, entry_id");
    }
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final BackupDataValidator validator;

    public BackupService(JdbcClient jdbc, ObjectMapper json, BackupDataValidator validator) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Backup export() {
        var data = snapshot();
        var backup = new Backup(1, Instant.now(), data, null);
        return new Backup(backup.formatVersion(), backup.createdAt(), data, backupChecksum(backup));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Backup backup) {
        validate(backup);
        var current = snapshot();
        return new Preview(backup.createdAt(), counts(backup.data()), counts(current), checksum(current));
    }

    @Transactional
    public Map<String, Integer> restore(Restore request) {
        validate(request.backup());
        try {
            jdbc.sql("LOCK TABLE " + String.join(", ", TABLES.keySet()) + " IN ACCESS EXCLUSIVE MODE NOWAIT").update();
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException cause && "55P03".equals(cause.getSQLState())) {
                throw new ApiException(HttpStatus.CONFLICT, "Данные сейчас используются. Повтори предварительную проверку восстановления.");
            }
            throw ex;
        }
        var current = snapshot();
        if (!checksum(current).equals(request.currentChecksum())) {
            throw new ApiException(HttpStatus.CONFLICT, "После предварительной проверки данные изменились. Проверь файл ещё раз.");
        }
        long version = jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM monthly_budget").query(Long.class).single();
        for (var table : TABLES.keySet().stream().toList().reversed()) jdbc.sql("DELETE FROM " + table).update();
        for (var table : TABLES.keySet()) {
            jdbc.sql("INSERT INTO " + table + " SELECT * FROM jsonb_populate_recordset(NULL::" + table + ", CAST(:rows AS jsonb))")
                    .param("rows", json.writeValueAsString(request.backup().data().get(table))).update();
        }
        // Reject lossy conversion, missing columns and unexpected fields before committing.
        if (!checksum(snapshot()).equals(checksum(request.backup().data()))) {
            throw new CalculationException("Структура или точность данных файла не совпадает с форматом приложения.");
        }
        // Also invalidate open forms when the imported copy contains no budget row.
        jdbc.sql("""
                INSERT INTO monthly_budget(id, entries, version)
                VALUES (1, '{"incomes":[],"expenses":[]}'::jsonb, :version + 1)
                ON CONFLICT (id) DO UPDATE SET version = GREATEST(monthly_budget.version, :version) + 1
                """)
                .param("version", version).update();
        return counts(request.backup().data());
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        TABLES.forEach((table, order) -> result.put(table,
                jdbc.sql("SELECT to_jsonb(row)::text FROM (SELECT * FROM " + table + " ORDER BY " + order + ") row")
                        .query(String.class).list().stream().map(value -> json.readValue(value, ROW)).toList()));
        return result;
    }

    private void validate(Backup backup) {
        if (backup == null || backup.formatVersion() != 1 || backup.createdAt() == null || backup.data() == null
                || !backup.data().keySet().equals(TABLES.keySet())
                || backup.data().values().stream().anyMatch(rows -> rows == null || rows.stream().anyMatch(row -> row == null))
                || backup.data().values().stream().mapToLong(List::size).sum() > 100000
                || !backupChecksum(backup).equals(backup.checksum())) {
            throw new CalculationException("Файл повреждён или имеет неподдерживаемый формат резервной копии.");
        }
        validator.validate(backup.data());
    }

    private Map<String, Integer> counts(Map<String, List<Map<String, Object>>> data) {
        var result = new LinkedHashMap<String, Integer>();
        data.forEach((table, rows) -> result.put(table, rows.size()));
        return result;
    }

    private String backupChecksum(Backup backup) {
        return checksum(Map.of("formatVersion", backup.formatVersion(), "createdAt", backup.createdAt().toString(), "data", backup.data()));
    }

    private String checksum(Object data) {
        try {
            byte[] value = json.writeValueAsString(canonical(data)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
