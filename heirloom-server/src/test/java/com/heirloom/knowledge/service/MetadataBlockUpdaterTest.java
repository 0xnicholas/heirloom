package com.heirloom.knowledge.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataBlockUpdaterTest {
    final MetadataBlockUpdater updater = new MetadataBlockUpdater();

    @Test void updateExistingBlock(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("test.md");
        Files.writeString(f, "pre\n<!-- HEIRLOOM_AUTO_START: schema -->\nold\n<!-- HEIRLOOM_AUTO_END: schema -->\npost");
        updater.updateBlock(f, "schema", "new cols");
        String result = Files.readString(f);
        assertThat(result).contains("new cols");
        assertThat(result).contains("pre");
        assertThat(result).contains("post");
        assertThat(result).doesNotContain("old");
    }

    @Test void addNewBlock(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("test.md");
        Files.writeString(f, "human content");
        updater.updateBlock(f, "schema", "auto content");
        String result = Files.readString(f);
        assertThat(result).contains("human content");
        assertThat(result).contains("HEIRLOOM_AUTO_START: schema");
        assertThat(result).contains("auto content");
    }

    @Test void getBlockContent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("test.md");
        Files.writeString(f, "<!-- HEIRLOOM_AUTO_START: schema -->\nmydata\n<!-- HEIRLOOM_AUTO_END: schema -->");
        assertThat(updater.getBlockContent(f, "schema")).isEqualTo("mydata");
    }

    @Test void missingBlockReturnsNull(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("test.md");
        Files.writeString(f, "no blocks");
        assertThat(updater.getBlockContent(f, "schema")).isNull();
    }
}
