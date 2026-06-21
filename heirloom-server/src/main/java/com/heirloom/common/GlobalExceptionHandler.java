package com.heirloom.common;

import com.heirloom.schema.service.TypeAlreadyExistsException;
import com.heirloom.schema.service.TypeNotFoundException;
import com.heirloom.schema.service.TypeValidationException;
import com.heirloom.schema.service.TypeValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TypeNotFoundException.class)
    public ProblemDetail handleNotFound(TypeNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Resource Type not found");
        pd.setType(URI.create("https://heirloom.dev/errors/type-not-found"));
        return pd;
    }

    @ExceptionHandler(TypeAlreadyExistsException.class)
    public ProblemDetail handleAlreadyExists(TypeAlreadyExistsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Resource Type already exists");
        pd.setType(URI.create("https://heirloom.dev/errors/type-already-exists"));
        return pd;
    }

    @ExceptionHandler(TypeValidationException.class)
    public ProblemDetail handleValidation(TypeValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Type validation failed");
        pd.setType(URI.create("https://heirloom.dev/errors/type-validation"));

        // Include diagnostics in the response
        List<Map<String, String>> diagnostics = ex.getDiagnostics().stream()
                .map(d -> Map.of(
                        "severity", d.severity().name().toLowerCase(),
                        "message", d.message()))
                .toList();
        pd.setProperty("diagnostics", diagnostics);

        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle("Invalid request");

        List<Map<String, String>> errors = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);

        return pd;
    }
}
