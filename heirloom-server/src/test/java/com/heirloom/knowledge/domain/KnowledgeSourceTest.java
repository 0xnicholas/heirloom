package com.heirloom.knowledge.domain;
import com.heirloom.core.entity.HeirloomEntity;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
class KnowledgeSourceTest {
    @Test void implementsHeirloomEntity() { var s=new KnowledgeSource(); s.setName("p"); assertThat(s).isInstanceOf(HeirloomEntity.class); assertThat(s.getEntityType()).isEqualTo("knowledgeSource"); }
    @Test void defaults() { var s=new KnowledgeSource(); assertThat(s.getSchedule()).isEqualTo("manual"); assertThat(s.getStatus()).isEqualTo("ACTIVE"); assertThat(s.getBranch()).isEqualTo("main"); }
    @Test void configDefaults() { assertThat(new KnowledgeSource().getConfig()).isEqualTo("{}"); }
}
