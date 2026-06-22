package com.heirloom.security.function;

import com.heirloom.security.domain.Function;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpelFunctionExecutorTest {

    private final SpelFunctionExecutor executor = new SpelFunctionExecutor();

    private static Function fn(String code) {
        Function f = new Function();
        f.setName("test");
        f.setCode(code);
        f.setOutputType("NUMBER");
        return f;
    }

    @Test
    void evaluatesArithmeticOnBoundVariables() {
        // Inputs are bound as SpEL variables — referenced via #name.
        Function f = fn("#amount * 0.1 + (#tier == 'gold' ? 50 : 0)");

        Object result = executor.execute(f, Map.of("amount", 1000, "tier", "gold"));

        assertThat(result).isEqualTo(150.0);
    }

    @Test
    void evaluatesWithLiteralOnly() {
        Function f = fn("42");
        assertThat(executor.execute(f, Map.of())).isEqualTo(42);
    }

    @Test
    void rejectsBlankCode() {
        Function f = fn("");
        assertThatThrownBy(() -> executor.execute(f, Map.of()))
                .isInstanceOf(FunctionExecutionException.class)
                .hasMessageContaining("no code");
    }

    @Test
    void rejectsUnparseableExpression() {
        Function f = fn("this is not valid SpEL @@@");
        assertThatThrownBy(() -> executor.execute(f, Map.of()))
                .isInstanceOf(FunctionExecutionException.class)
                .hasMessageContaining("parse");
    }

    @Test
    void rejectsMethodInvocation_sandbox() {
        // SimpleEvaluationContext.forReadOnlyDataBinding() disallows method calls.
        Function f = fn("#input.toUpperCase()");
        assertThatThrownBy(() -> executor.execute(f, Map.of("input", "hello")))
                .isInstanceOf(FunctionExecutionException.class);
    }

    @Test
    void handlesNullInputs() {
        Function f = fn("42");
        assertThat(executor.execute(f, null)).isEqualTo(42);
    }

    @Test
    void eachInvocationHasIsolatedVariables() {
        // Two consecutive calls with different inputs must not bleed into each other.
        // Use 1.0 literals to force Double return type.
        Function f = fn("#x * 2.0");
        Object a = executor.execute(f, Map.of("x", 10.0));
        Object b = executor.execute(f, Map.of("x", 20.0));
        assertThat(a).isEqualTo(20.0);
        assertThat(b).isEqualTo(40.0);
    }

    @Test
    void backendNameIsStable() {
        assertThat(executor.backendName()).isEqualTo("spel-readonly");
    }
}