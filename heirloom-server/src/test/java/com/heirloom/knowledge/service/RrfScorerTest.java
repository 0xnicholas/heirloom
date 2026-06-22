package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RrfScorerTest {
    final RrfScorer scorer = new RrfScorer();
    KnowledgeArticle mk(long id) { KnowledgeArticle a=new KnowledgeArticle(); a.setFullyQualifiedName("k."+id); return a; }

    @Test void fusesAndSorts() {
        var fts = List.of(mk(1), mk(2), mk(3));
        var vec = List.of(mk(2), mk(3), mk(4));
        var r = scorer.fuse(fts, vec);
        assertThat(r).hasSize(4);
        assertThat(r.get(0).article().getFullyQualifiedName()).isEqualTo("k.2"); // RRF 0.0325
        assertThat(r.get(1).article().getFullyQualifiedName()).isEqualTo("k.3"); // RRF 0.0317
    }

    @Test void emptyResults() { assertThat(scorer.fuse(List.of(), List.of())).isEmpty(); }
    @Test void singleResult() { var r=scorer.fuse(List.of(mk(1)),List.of()); assertThat(r).hasSize(1); }
}
