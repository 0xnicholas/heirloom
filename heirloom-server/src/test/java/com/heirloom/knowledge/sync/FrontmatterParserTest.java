package com.heirloom.knowledge.sync;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class FrontmatterParserTest {
    final FrontmatterParser p = new FrontmatterParser();
    @Test void valid() { var r=p.parse("---\ntype: T\ntitle: X\ntags: [a,b]\n---\n# Body\n", "f.md"); assertThat(r.hasErrors()).isFalse(); assertThat(r.frontmatter().get("type")).isEqualTo("T"); assertThat(r.body()).contains("# Body"); }
    @Test void missingType() { var r=p.parse("---\ntitle: X\n---\nB\n", "f.md"); assertThat(r.hasErrors()).isTrue(); assertThat(r.errors().get(0).errorType()).isEqualTo("MISSING_TYPE"); }
    @Test void noFrontmatter() { var r=p.parse("# MD\n", "f.md"); assertThat(r.errors().get(0).errorType()).isEqualTo("MISSING_TYPE"); }
    @Test void emptyFrontmatter() { var r=p.parse("---\n---\nB\n", "f.md"); assertThat(r.errors().get(0).errorType()).isEqualTo("MISSING_TYPE"); }
    @Test void tagString() { assertThat(FrontmatterParser.normalizeTags("s")).containsExactly("s"); }
    @Test void tagArray() { assertThat(FrontmatterParser.normalizeTags(List.of("a","b"))).containsExactly("a","b"); }
    @Test void tagNull() { assertThat(FrontmatterParser.normalizeTags(null)).isEmpty(); }
    @Test void customFields() { var r=p.parse("---\ntype: T\nowner: team\n---\nB\n", "f.md"); assertThat(r.frontmatter().get("x_owner")).isEqualTo("team"); }
    @Test void deriveTitle() { var r=p.parse("---\ntype: T\n---\nB\n", "my-cat.md"); assertThat(r.frontmatter().get("title")).isEqualTo("My Cat"); }
    @Test void invalidYaml() { var r=p.parse("---\ntype: T\ntags: [bad\n---\nB\n", "f.md"); assertThat(r.errors().get(0).errorType()).isEqualTo("PARSE_ERROR"); }
    @Test void deriveNull() { assertThat(FrontmatterParser.deriveTitle(null)).isEqualTo("Untitled"); }
    @Test void deriveEmpty() { assertThat(FrontmatterParser.deriveTitle("")).isEqualTo("Untitled"); }
    @Test void statusField() { var r=p.parse("---\ntype: T\nstatus: review\n---\nB\n", "f.md"); assertThat(r.frontmatter().get("status")).isEqualTo("review"); }
    @Test void statusDefaultNull() { var r=p.parse("---\ntype: T\n---\nB\n", "f.md"); assertThat(r.frontmatter().get("status")).isNull(); }
}
