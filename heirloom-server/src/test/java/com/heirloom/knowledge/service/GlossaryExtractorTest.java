package com.heirloom.knowledge.service;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class GlossaryExtractorTest {
    final GlossaryExtractor e = new GlossaryExtractor();

    @Test void simpleTerm() {
        var r = e.extract("**活跃客户**：过去30天有交易的客户", "a.md");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).term()).isEqualTo("活跃客户");
        assertThat(r.get(0).definition()).isEqualTo("过去30天有交易的客户");
    }

    @Test void termWithEnglish() {
        var r = e.extract("**Active Customer (活跃客户)**: A customer with orders in 30 days", "a.md");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).term()).isEqualTo("Active Customer");
        assertThat(r.get(0).englishName()).isEqualTo("活跃客户");
    }

    @Test void multipleTerms() {
        var r = e.extract("**A**：def one here\n\n**B**：def two here also", "a.md");
        assertThat(r).hasSize(2);
    }

    @Test void ignoresShortDefinitions() {
        var r = e.extract("**X**：ab", "a.md"); // definition length 2 < 5
        assertThat(r).isEmpty();
    }

    @Test void emptyBody() {
        assertThat(e.extract("", "a.md")).isEmpty();
        assertThat(e.extract(null, "a.md")).isEmpty();
    }
}
