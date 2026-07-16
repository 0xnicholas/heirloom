package com.heirloom.profiling.service;

import com.heirloom.core.profiling.ColumnProfileResult;
import com.heirloom.core.profiling.DataClass;
import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.core.profiling.ProfilingService;
import com.heirloom.core.profiling.ValueFrequency;
import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.metadata.repository.ColumnProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProfilingServiceImpl implements ProfilingService {
    private static final Logger log = LoggerFactory.getLogger(ProfilingServiceImpl.class);
    private static final long SAMPLE_THRESHOLD = 1_000_000L;
    private static final double SAMPLE_RATE = 0.1;
    private static final int TOP_VALUES_LIMIT = 5;

    private final DataSource dataSource;
    private final ColumnProfileRepository columnProfileRepo;
    private final ColumnProfileCleanupService cleanupService;
    private final PostgresSamplingStrategy samplingStrategy;

    public ProfilingServiceImpl(DataSource dataSource, ColumnProfileRepository columnProfileRepo,
                                ColumnProfileCleanupService cleanupService) {
        this.dataSource = dataSource;
        this.columnProfileRepo = columnProfileRepo;
        this.cleanupService = cleanupService;
        this.samplingStrategy = new PostgresSamplingStrategy(SAMPLE_RATE);
    }

    @Override
    public ProfileReport profile(String tableFQN) {
        long start = System.currentTimeMillis();

        long rowCount = countRows(tableFQN);
        boolean useSampling = rowCount > SAMPLE_THRESHOLD;
        String source = useSampling ? samplingStrategy.apply(tableFQN, rowCount) : tableFQN;

        List<String> columns = listColumns(tableFQN);
        List<ColumnProfileResult> results = new ArrayList<>();

        for (String colName : columns) {
            try {
                ColumnProfileResult cpr = profileColumn(tableFQN, colName);
                results.add(cpr);
                persist(tableFQN, colName, cpr);
                cleanupService.cleanup(tableFQN, colName);
            } catch (Exception e) {
                log.warn("Failed to profile column {}.{}: {}", tableFQN, colName, e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - start;
        double overall = results.stream().mapToDouble(ColumnProfileResult::qualityScore).average().orElse(0.0);

        return new ProfileReport(tableFQN, rowCount, columns.size(), Instant.now(), duration, results, overall);
    }

    @Override
    public ColumnProfileResult profileColumn(String tableFQN, String columnName) {
        long rowCount = countRows(tableFQN);
        boolean useSampling = rowCount > SAMPLE_THRESHOLD;
        String source = useSampling ? samplingStrategy.apply(tableFQN, rowCount) : tableFQN;

        String dataType = getDataType(tableFQN, columnName);
        long nullCount = countNulls(source, columnName);
        double nullRate = rowCount > 0 ? (double) nullCount / rowCount : 0.0;
        long distinctCount = countDistinct(source, columnName);
        double distinctRate = rowCount > 0 ? (double) distinctCount / rowCount : 0.0;
        long emptyCount = countEmptyStrings(source, columnName);
        String minVal = getMin(source, columnName);
        String maxVal = getMax(source, columnName);
        Double avgLen = getAvgLength(source, columnName);
        List<ValueFrequency> topValues = queryTopValues(source, columnName);

        DataClass detected = DataClassInferrer.infer(columnName, dataType, distinctCount, distinctRate,
                rowCount, topValues, minVal, maxVal, avgLen);

        double quality = QualityScorer.score(nullRate, distinctRate, dataType, topValues, avgLen);

        return new ColumnProfileResult(columnName, dataType, nullCount, nullRate,
                distinctCount, distinctRate, emptyCount, minVal, maxVal, avgLen, topValues, detected, quality);
    }

    private List<String> listColumns(String tableFQN) {
        List<String> cols = new ArrayList<>();
        String[] parts = parseTableFQN(tableFQN);
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parts[0]);
            ps.setString(2, parts[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString("column_name"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list columns for {}: {}", tableFQN, e.getMessage());
        }
        return cols;
    }

    private long countRows(String tableFQN) {
        String sql = "SELECT COUNT(*) FROM " + tableFQN;
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.warn("Failed to count rows for {}: {}", tableFQN, e.getMessage());
        }
        return 0;
    }

    private long countNulls(String source, String columnName) {
        String sql = "SELECT COUNT(*) FROM " + source + " WHERE " + columnName + " IS NULL";
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.warn("Failed to count nulls for {}.{}: {}", source, columnName, e.getMessage());
        }
        return 0;
    }

    private long countDistinct(String source, String columnName) {
        String sql = "SELECT COUNT(DISTINCT " + columnName + ") FROM " + source;
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.warn("Failed to count distinct for {}.{}: {}", source, columnName, e.getMessage());
        }
        return 0;
    }

    private long countEmptyStrings(String source, String columnName) {
        String sql = "SELECT COUNT(*) FROM " + source + " WHERE " + columnName + " = ''";
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.warn("Failed to count distinct for {}.{}: {}", source, columnName, e.getMessage());
        }
        return 0;
    }

    private String getMin(String source, String columnName) {
        String sql = "SELECT MIN(" + columnName + "::text) FROM " + source;
        return queryScalar(sql);
    }

    private String getMax(String source, String columnName) {
        String sql = "SELECT MAX(" + columnName + "::text) FROM " + source;
        return queryScalar(sql);
    }

    private Double getAvgLength(String source, String columnName) {
        String sql = "SELECT AVG(LENGTH(" + columnName + "::text)) FROM " + source;
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                double v = rs.getDouble(1);
                return rs.wasNull() ? null : v;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String getDataType(String tableFQN, String columnName) {
        String[] parts = parseTableFQN(tableFQN);
        String sql = "SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parts[0]);
            ps.setString(2, parts[1]);
            ps.setString(3, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("data_type");
            }
        } catch (Exception e) {
            log.warn("Failed to get data type for {}.{}: {}", tableFQN, columnName, e.getMessage());
        }
        return "unknown";
    }

    private List<ValueFrequency> queryTopValues(String source, String columnName) {
        List<ValueFrequency> top = new ArrayList<>();
        String sql = "SELECT " + columnName + "::text AS val, COUNT(*) AS cnt FROM " + source
                + " WHERE " + columnName + " IS NOT NULL GROUP BY val ORDER BY cnt DESC LIMIT " + TOP_VALUES_LIMIT;
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            long total = 0;
            List<String> vals = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            while (rs.next()) {
                vals.add(rs.getString("val"));
                long cnt = rs.getLong("cnt");
                counts.add(cnt);
                total += cnt;
            }
            for (int i = 0; i < vals.size(); i++) {
                double freq = total > 0 ? (double) counts.get(i) / total : 0.0;
                top.add(new ValueFrequency(vals.get(i), counts.get(i), freq));
            }
        } catch (Exception e) {
            log.warn("Failed to query top values for {}.{}: {}", source, columnName, e.getMessage());
        }
        return top;
    }

    private String queryScalar(String sql) {
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getString(1);
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void persist(String tableFQN, String columnName, ColumnProfileResult cpr) {
        try {
            ColumnProfileEntity entity = new ColumnProfileEntity();
            entity.setTableFQN(tableFQN);
            entity.setColumnName(columnName);
            entity.setNullCount(cpr.nullCount());
            entity.setNullRate(cpr.nullRate());
            entity.setDistinctCount(cpr.distinctCount());
            entity.setDistinctRate(cpr.distinctRate());
            entity.setEmptyStringCount(cpr.emptyStringCount());
            entity.setMinValue(cpr.minValue());
            entity.setMaxValue(cpr.maxValue());
            entity.setAvgLength(cpr.avgLength());
            entity.setDetectedClass(cpr.detectedClass().name());
            entity.setQualityScore(cpr.qualityScore());
            columnProfileRepo.create(entity);
        } catch (Exception e) {
            log.warn("Failed to persist profile for {}.{}: {}", tableFQN, columnName, e.getMessage());
        }
    }

    private static String[] parseTableFQN(String tableFQN) {
        int dot = tableFQN.indexOf('.');
        if (dot > 0) {
            return new String[] { tableFQN.substring(0, dot), tableFQN.substring(dot + 1) };
        }
        return new String[] { "public", tableFQN };
    }

    DataSource getDataSource() { return dataSource; }
    ColumnProfileRepository getColumnProfileRepo() { return columnProfileRepo; }
}
