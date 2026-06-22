package com.heirloom.knowledge.exception;
public class SyncException extends RuntimeException {
    public SyncException(String m) { super(m); }
    public SyncException(String m, Throwable c) { super(m, c); }
}
