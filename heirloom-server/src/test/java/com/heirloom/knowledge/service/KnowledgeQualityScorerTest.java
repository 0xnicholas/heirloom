package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.EntityReference;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeQualityScorerTest {
    final KnowledgeQualityScorer scorer = new KnowledgeQualityScorer();
    KnowledgeArticle mk(String title, String body, int refs, Instant synced) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setTitle(title); a.setDescription("desc"); a.setBody(body); a.setType("T"); a.setDomain("d");
        a.setTags(List.of("tag")); a.setLastSyncedAt(synced);
        for (int i=0;i<refs;i++) a.getReferences().add(new EntityReference("f"+i,"T","l","t"));
        a.setFileHash("abc"); a.setChangeHash("abc"); return a;
    }
    @Test void excellent() { var s=scorer.score(mk("O","B ".repeat(500),3,Instant.now())); assertThat(s.tier()).isEqualTo("excellent"); }
    @Test void minimal() { var s=scorer.score(mk("X","s",0,Instant.now().minus(200,ChronoUnit.DAYS))); assertThat(s.tier()).isIn("fair","poor"); }
    @Test void nullBody() { var s=scorer.score(mk(null,null,0,null)); assertThat(s.dimensions().get("richness")).isEqualTo(0.0); }
}
