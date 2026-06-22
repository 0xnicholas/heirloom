package com.heirloom.knowledge.sync;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
class LinkResolverTest {
    final LinkResolver r = new LinkResolver();
    @Test void httpLink() { var res = r.resolve("[Google](https://google.com)"); assertThat(res.citations()).hasSize(1); assertThat(res.citations().get(0).url()).isEqualTo("https://google.com"); }
    @Test void mdLink() { var res = r.resolve("[orders](/tables/orders.md)"); assertThat(res.references()).hasSize(1); assertThat(res.references().get(0).fqn()).isEqualTo("knowledge.tables.orders"); }
    @Test void relativeMdLink() { var res = r.resolve("[other](./other.md)"); assertThat(res.references()).hasSize(1); assertThat(res.references().get(0).fqn()).contains("other"); }
    @Test void mixed() { var res = r.resolve("[a](https://x.com) and [b](/t.md)"); assertThat(res.citations()).hasSize(1); assertThat(res.references()).hasSize(1); }
    @Test void empty() { var res = r.resolve(""); assertThat(res.references()).isEmpty(); assertThat(res.citations()).isEmpty(); }
    @Test void noLinks() { var res = r.resolve("plain text"); assertThat(res.references()).isEmpty(); }
}
