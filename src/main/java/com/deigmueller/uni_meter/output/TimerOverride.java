package com.deigmueller.uni_meter.output;

import com.typesafe.config.Config;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

@ToString
public class TimerOverride {
    // Instance members
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private final LocalTime from;
    private final LocalTime to;
    private final Set<Integer> daysOfWeek = new HashSet<>();
    private final Set<Integer> daysOfMonth = new HashSet<>();
    private final Set<Integer> daysOfYear = new HashSet<>();
    private final Set<Integer> months = new HashSet<>();
    private final Set<Integer> weeksOfYear = new HashSet<>();
    private final Set<Integer> years = new HashSet<>();
    @Getter private final Double minPower;
    @Getter private final Double maxPower;
    
    public TimerOverride(@NotNull Config config) {
        if (config.hasPath("from")) {
            this.from = LocalTime.parse(config.getString("from"), TIME_FORMATTER);
        } else {
            this.from = LocalTime.MIN;
        }
        if (config.hasPath("to")) {
            this.to = LocalTime.parse(config.getString("to"), TIME_FORMATTER);
        } else {
            this.to = null;
        }
        
        if (config.hasPath("days-of-week")) {
            for (String dayOfWeek : config.getStringList("days-of-week")) {
                daysOfWeek.add(DayOfWeek.valueOf(dayOfWeek).getValue());
            }
        }
        
        if (config.hasPath("days-of-month")) {
            daysOfMonth.addAll(config.getIntList("days-of-month"));
        }
        
        if (config.hasPath("days-of-year")) {
            daysOfYear.addAll(config.getIntList("days-of-year"));
        }
        
        if (config.hasPath("months")) {
            months.addAll(config.getIntList("months"));
        }
        
        if (config.hasPath("weeks-of-year")) {
            weeksOfYear.addAll(config.getIntList("weeks-of-year"));
        }
        
        if (config.hasPath("years")) {
            years.addAll(config.getIntList("years"));
        }
        
        if (config.hasPath("min-power")) {
            this.minPower = config.getDouble("min-power");
        } else {
            this.minPower = null;
        }

        if (config.hasPath("max-power")) {
            this.maxPower = config.getDouble("max-power");
        } else {
            this.maxPower = null;
        }
    }

    public boolean matches(@NotNull LocalDateTime dateTime) {
        boolean result;
        if (to == null) {
            result = !dateTime.toLocalTime().isBefore(from);
        } else {
            if (from.isAfter(to)) {
                result = dateTime.toLocalTime().isBefore(from) || !dateTime.toLocalTime().isBefore(to);
            } else {
                result = !dateTime.toLocalTime().isBefore(from) && dateTime.toLocalTime().isBefore(to);
            }
        }
        if (!result) {
            return false;
        }

        if (!daysOfWeek.isEmpty() && !daysOfWeek.contains(dateTime.getDayOfWeek().getValue())) {
            return false;
        }
        
        if (!daysOfMonth.isEmpty() && !daysOfMonth.contains(dateTime.getDayOfMonth())) {
            return false;
        }
        
        if (!daysOfYear.isEmpty() && !daysOfYear.contains(dateTime.getDayOfYear())) {
            return false;
        }
        
        if (!months.isEmpty() && !months.contains(dateTime.getMonthValue())) {
            return false;
        }
        
        if (!weeksOfYear.isEmpty() && !weeksOfYear.contains(dateTime.getDayOfYear() / 7)) {
            return false;
        }

        return years.isEmpty() || years.contains(dateTime.getYear());
    }
}
