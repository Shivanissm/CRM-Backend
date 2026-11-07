package com.brideside.crm.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Deal times are saved as India wall-clock values. On a UTC server the JDBC
 * read turns that clock back by 5 hours 30 minutes before JSON is written.
 * This puts the saved clock back on the way out, and leaves an India server unchanged.
 */
public class IndiaWallClockSerializer extends JsonSerializer<LocalDateTime> {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        LocalDateTime wallClock = value.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(INDIA)
                .toLocalDateTime();
        gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(wallClock));
    }
}
