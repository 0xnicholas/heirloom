package com.heirloom.knowledge.domain;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
class KnowledgeStatusTest {
    @Test void draftToReview() { assertThat(KnowledgeStatus.DRAFT.canTransitionTo(KnowledgeStatus.REVIEW)).isTrue(); }
    @Test void draftToPublished() { assertThat(KnowledgeStatus.DRAFT.canTransitionTo(KnowledgeStatus.PUBLISHED)).isTrue(); }
    @Test void draftToArchived() { assertThat(KnowledgeStatus.DRAFT.canTransitionTo(KnowledgeStatus.ARCHIVED)).isFalse(); }
    @Test void reviewToPublished() { assertThat(KnowledgeStatus.REVIEW.canTransitionTo(KnowledgeStatus.PUBLISHED)).isTrue(); }
    @Test void reviewToDraft() { assertThat(KnowledgeStatus.REVIEW.canTransitionTo(KnowledgeStatus.DRAFT)).isTrue(); }
    @Test void publishedToArchived() { assertThat(KnowledgeStatus.PUBLISHED.canTransitionTo(KnowledgeStatus.ARCHIVED)).isTrue(); }
    @Test void publishedToDraft() { assertThat(KnowledgeStatus.PUBLISHED.canTransitionTo(KnowledgeStatus.DRAFT)).isTrue(); }
    @Test void archivedToPublished() { assertThat(KnowledgeStatus.ARCHIVED.canTransitionTo(KnowledgeStatus.PUBLISHED)).isTrue(); }
    @Test void archivedToDraft() { assertThat(KnowledgeStatus.ARCHIVED.canTransitionTo(KnowledgeStatus.DRAFT)).isFalse(); }
    @Test void fromNull() { assertThat(KnowledgeStatus.fromString(null)).isEqualTo(KnowledgeStatus.PUBLISHED); }
    @Test void fromBlank() { assertThat(KnowledgeStatus.fromString("")).isEqualTo(KnowledgeStatus.PUBLISHED); }
    @Test void fromLowercase() { assertThat(KnowledgeStatus.fromString("draft")).isEqualTo(KnowledgeStatus.DRAFT); }
    @Test void fromInvalid() { assertThat(KnowledgeStatus.fromString("invalid")).isEqualTo(KnowledgeStatus.PUBLISHED); }
}
