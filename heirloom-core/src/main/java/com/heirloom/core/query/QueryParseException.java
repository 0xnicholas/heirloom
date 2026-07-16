package com.heirloom.core.query;

/**
 * Thrown when a query request fails validation — unknown type, undeclared field,
 * or illegal operation.
 */
public class QueryParseException extends RuntimeException {

    public QueryParseException(String message) {
        super(message);
    }
}
