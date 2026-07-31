package dev.vilquer.petcarescheduler.infra.adapter.output.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Optional;

/** DTOs públicos usados pelo SDK para gerar JSON Schemas estritos. */
public final class OpenAiStructuredOutputs {
    private OpenAiStructuredOutputs() {}

    public enum DraftField {
        PET, TYPE, TITLE, INSTRUCTIONS, START_AT, TIMEZONE, SCHEDULE,
        REMINDER, RESPONSIBLE, CRITICAL, ESCALATION, ESTIMATED_COST
    }

    public enum DraftEventType { VACCINE, MEDICINE, SERVICE, DIARY, BREED }
    public enum DraftProvenance { EXPLICIT, NORMALIZED, NEEDS_REVIEW, MISSING }
    public enum ScheduleKind { ONE_TIME, CALENDAR_INTERVAL, FIXED_INTERVAL, DAILY_TIMES }
    public enum CalendarUnit { DAY, WEEK, MONTH, YEAR }

    public static final class FieldEvidence {
        public DraftField field;
        @JsonPropertyDescription("Trecho literal curto da instrução que sustenta o campo.")
        public String evidence;
        public DraftProvenance provenance;
    }

    public static final class Warning {
        @JsonPropertyDescription("Código estável em maiúsculas e snake case.")
        public String code;
        public String message;
        public boolean blocking;
    }

    public static final class Schedule {
        public ScheduleKind kind;
        public Optional<String> calendarUnit;
        public Optional<Long> intervalCount;
        public Optional<Long> fixedIntervalMinutes;
        @JsonPropertyDescription("Horários locais no formato HH:mm, ordenados e sem duplicatas.")
        public List<String> dailyTimes;
        public Optional<Long> repetitions;
        @JsonPropertyDescription("Instant ISO-8601 ou ausente.")
        public Optional<String> endAt;
    }

    public static final class CareDraftOutput {
        public Optional<String> petReference;
        public Optional<String> type;
        public Optional<String> title;
        public Optional<String> instructions;
        @JsonPropertyDescription("Instant ISO-8601 ou ausente quando a data ou hora for ambígua.")
        public Optional<String> startAt;
        public Optional<String> timezone;
        public Optional<Schedule> schedule;
        public List<FieldEvidence> evidence;
        public List<DraftField> missingFields;
        public List<Warning> warnings;
    }

    public static final class GroundedAnswerOutput {
        @JsonPropertyDescription("Resposta em português baseada apenas nos trechos fornecidos.")
        public String answer;
        @JsonPropertyDescription("Somente referências opacas de chunks presentes na evidência fornecida, como chunk-1.")
        public List<String> citedChunkReferences;
        public boolean insufficientEvidence;
    }
}
