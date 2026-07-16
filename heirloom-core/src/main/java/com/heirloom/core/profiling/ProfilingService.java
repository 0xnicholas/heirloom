package com.heirloom.core.profiling;

public interface ProfilingService {
    ProfileReport profile(String tableFQN);
    ColumnProfileResult profileColumn(String tableFQN, String columnName);
}
