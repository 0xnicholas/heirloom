package com.heirloom.knowledge.exception;
public class FrontmatterParseException extends SyncException {
    private final String filePath, errorType;
    public FrontmatterParseException(String fp, String m, String et) { super(m); filePath=fp; errorType=et; }
    public String getFilePath() { return filePath; }
    public String getErrorType() { return errorType; }
}
