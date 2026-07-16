package com.heirloom.metadata.domain;

import com.heirloom.core.metadata.ColumnDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColumnDefParserTest {

    @Test
    @DisplayName("parses new format column definitions")
    void shouldParseNewFormat() {
        String json = "[{\"name\":\"id\",\"dataType\":\"integer\",\"nullable\":false,\"ordinalPosition\":0,\"tags\":[]}]";
        List<ColumnDef> defs = ColumnDefParser.parse(json);
        assertEquals(1, defs.size());
        assertEquals("id", defs.get(0).name());
        assertEquals("integer", defs.get(0).dataType());
    }

    @Test
    @DisplayName("parses legacy format with type field instead of dataType")
    void shouldParseLegacyFormat() {
        String json = "[{\"name\":\"legacy_col\",\"type\":\"text\"}]";
        List<ColumnDef> defs = ColumnDefParser.parse(json);
        assertEquals(1, defs.size());
        assertEquals("legacy_col", defs.get(0).name());
        assertEquals("text", defs.get(0).dataType());
    }

    @Test
    @DisplayName("returns empty list for invalid JSON")
    void shouldReturnEmptyForInvalidJson() {
        List<ColumnDef> defs = ColumnDefParser.parse("not json");
        assertEquals(0, defs.size());
    }

    @Test
    @DisplayName("returns empty list for null input")
    void shouldReturnEmptyForNull() {
        List<ColumnDef> defs = ColumnDefParser.parse("null");
        assertEquals(0, defs.size());
    }

    @Test
    @DisplayName("handles empty array")
    void shouldHandleEmptyArray() {
        List<ColumnDef> defs = ColumnDefParser.parse("[]");
        assertEquals(0, defs.size());
    }
}
