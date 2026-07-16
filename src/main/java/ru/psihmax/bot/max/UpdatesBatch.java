package ru.psihmax.bot.max;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record UpdatesBatch(List<JsonNode> updates, Long marker) {
}
