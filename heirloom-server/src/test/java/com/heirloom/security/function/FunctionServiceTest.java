package com.heirloom.security.function;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogRepository;
import com.heirloom.repository.FunctionRepository;
import com.heirloom.security.domain.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FunctionServiceTest {

    private final FunctionRepository repo = mock(FunctionRepository.class);
    private final SpelFunctionExecutor executor = new SpelFunctionExecutor();
    private final EventLogRepository eventLog = mock(EventLogRepository.class);
    private final FunctionService service = new FunctionService(repo, executor, eventLog);

    private static Function fn(String code, boolean audit) {
        Function f = new Function();
        f.setName("risk_score");
        f.setCode(code);
        f.setOutputType("NUMBER");
        f.setAuditEnabled(audit);
        return f;
    }

    @Test
    void invokesAndReturnsResult() {
        when(repo.findByName("risk_score")).thenReturn(Optional.of(fn("#amount * 0.1", false)));

        Object result = service.invoke("risk_score", Map.of("amount", 500), "agent:007");

        assertThat(result).isEqualTo(50.0);
        verifyNoInteractions(eventLog);
    }

    @Test
    void emitsAuditEventWhenEnabled() {
        when(repo.findByName("risk_score")).thenReturn(Optional.of(fn("#amount * 0.1", true)));

        service.invoke("risk_score", Map.of("amount", 500), "agent:007");

        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.FUNCTION_INVOKED);
        assertThat(event.getActor()).isEqualTo("agent:007");
        assertThat(event.getChangeHash()).contains("OK").contains("ms");
    }

    @Test
    void auditLogsFailuresToo() {
        when(repo.findByName("risk_score")).thenReturn(Optional.of(fn("invalid@@@", true)));

        assertThatThrownBy(() -> service.invoke("risk_score", Map.of(), "agent:007"))
                .isInstanceOf(FunctionExecutionException.class);

        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        assertThat(captor.getValue().getChangeHash()).startsWith("FAILED");
    }

    @Test
    void unknownFunctionRaises() {
        when(repo.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invoke("ghost", Map.of(), "user:alice"))
                .isInstanceOf(FunctionExecutionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void softDeletedFunctionRejected() {
        Function deleted = fn("#amount", false);
        deleted.setDeleted(true);
        when(repo.findByName("risk_score")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.invoke("risk_score", Map.of("amount", 1), "user:alice"))
                .isInstanceOf(FunctionExecutionException.class);
    }
}