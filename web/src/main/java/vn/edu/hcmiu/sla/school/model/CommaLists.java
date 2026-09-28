package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Short lists kept in one column as comma-separated text: "event,training_points", "2026-09-29,2026-10-02". */
public final class CommaLists {

    private CommaLists() {
    }

    /** Words without commas (the mail categories). An empty list is "", a missing one NULL. */
    @Converter
    public static class Words implements AttributeConverter<List<String>, String> {

        @Override
        public String convertToDatabaseColumn(List<String> words) {
            return words == null ? null : String.join(",", words);
        }

        @Override
        public List<String> convertToEntityAttribute(String column) {
            if (column == null) {
                return null;
            }
            return column.isEmpty() ? List.of() : List.of(column.split(","));
        }
    }

    /** ISO dates. */
    @Converter
    public static class Dates implements AttributeConverter<List<LocalDate>, String> {

        @Override
        public String convertToDatabaseColumn(List<LocalDate> dates) {
            return dates == null ? null : String.join(",", dates.stream().map(LocalDate::toString).toList());
        }

        @Override
        public List<LocalDate> convertToEntityAttribute(String column) {
            if (column == null || column.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(column.split(",")).map(LocalDate::parse).toList();
        }
    }
}
