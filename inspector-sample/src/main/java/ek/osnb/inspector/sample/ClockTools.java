package ek.osnb.inspector.sample;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class ClockTools {

    // An unknown zone id throws, which shows how a failing tool looks in the inspector.
    @Tool(description = "Current date and time in a time zone")
    public String currentTime(@ToolParam(description = "IANA time zone id, e.g. Europe/Copenhagen") String zone) {
        return ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
