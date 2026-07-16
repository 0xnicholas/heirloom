package com.heirloom.core.pipeline;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PipelineFailureTest {

    @Test
    void recoverableFailureExtendsPipelineFailure() {
        PipelineFailure failure = new RecoverableFailure("network timeout");
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(failure.getMessage()).isEqualTo("network timeout");
    }

    @Test
    void fatalFailureExtendsPipelineFailure() {
        PipelineFailure failure = new FatalFailure("permission denied");
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(failure.getMessage()).isEqualTo("permission denied");
    }

    @Test
    void recoverableFailureWithCause() {
        Throwable cause = new IllegalStateException("upstream");
        var failure = new RecoverableFailure("retry me", cause);
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @Test
    void fatalFailureWithCause() {
        Throwable cause = new IllegalArgumentException("bad config");
        var failure = new FatalFailure("config error", cause);
        assertThat(failure.getCause()).isSameAs(cause);
    }
}