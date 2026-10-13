package io.github.osmanbutt.inspector.sample;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeatherTools {

    public record Weather(String city, int temperatureCelsius, String conditions) {
    }

    private static final List<String> CONDITIONS = List.of("sunny", "cloudy", "rainy", "windy", "snowy");

    /** Made-up but stable weather, so the sample needs no weather API. */
    @Tool(description = "Current weather for a city")
    public Weather weather(@ToolParam(description = "City name") String city) {
        int seed = Math.abs(city.toLowerCase().hashCode());
        return new Weather(city, seed % 25 - 2, CONDITIONS.get(seed % CONDITIONS.size()));
    }
}
