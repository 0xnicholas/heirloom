package com.heirloom.schema.service;

import java.util.List;

public class TypeValidationException extends RuntimeException {
    private final List<TypeValidator.Diagnostic> diagnostics;

    public TypeValidationException(List<TypeValidator.Diagnostic> diagnostics) {
        super("Type validation failed: "
              + diagnostics.stream()
                  .filter(d -> d.severity() == TypeValidator.Severity.ERROR)
                  .map(TypeValidator.Diagnostic::message)
                  .collect(java.util.stream.Collectors.joining("; ")));
        this.diagnostics = diagnostics;
    }

    public List<TypeValidator.Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
