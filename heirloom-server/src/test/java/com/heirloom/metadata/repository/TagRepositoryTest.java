package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.TagEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({TagRepository.class})
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=never",
    "spring.jpa.hibernate.ddl-auto=create"
})
@DisplayName("TagRepository")
class TagRepositoryTest {

    @Autowired
    private TagJpaRepository jpa;

    @Autowired
    private TagRepository repo;

    @Test
    @DisplayName("JPA: persists and finds by fully qualified name")
    void jpaPersistsAndFindsByFQN() {
        var entity = new TagEntity();
        entity.setName("PII");
        entity.setDescription("Personally Identifiable Information tag");
        entity.setClassificationFQN("PII");
        entity.setFullyQualifiedName("PII");
        var saved = jpa.save(entity);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFullyQualifiedName()).isEqualTo("PII");

        var found = jpa.findByFullyQualifiedName("PII");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("PII");
    }

    @Test
    @DisplayName("JPA: returns empty for non-existent name")
    void jpaReturnsEmptyForUnknownName() {
        var found = jpa.findByName("nonexistent");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("JPA: persists and then deletes")
    void jpaPersistsAndDeletes() {
        var entity = new TagEntity();
        entity.setName("Sensitive");
        entity.setFullyQualifiedName("Sensitive");
        var saved = jpa.save(entity);

        jpa.deleteById(saved.getId());

        var found = jpa.findById(saved.getId());
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("EntityRepository: creates entity with FQN set")
    void entityRepoCreatesWithFQN() {
        var entity = new TagEntity();
        entity.setName("EntityRepo");
        entity.setDescription("Testing entity repo");
        var created = repo.create(entity);
        assertThat(created.getFullyQualifiedName()).isEqualTo("EntityRepo");
    }
}
