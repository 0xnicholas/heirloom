package com.heirloom.duckdb;

import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.metadata.repository.ColumnProfileRepository;
import com.heirloom.repository.TableRepository;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DuckDbSyncServiceTest {

    @Mock DuckDbRawStore duckDb;
    @Mock DataSource sourceDataSource;
    @Mock TableRepository tableRepo;
    @Mock ColumnProfileRepository profileRepo;
    @Mock Connection pgConn;
    @Mock PreparedStatement pgStmt;
    @Mock ResultSet pgRs;
    @Mock DuckDBConnection duckConn;
    @Mock DuckDBAppender appender;

    @Test
    void shouldSyncTableWithDDLAndAppender() throws Exception {
        String fqn = "prod.pg.public.orders";

        TableEntity table = new TableEntity();
        table.setColumnsJson("[{\"name\":\"id\",\"dataType\":\"integer\"},{\"name\":\"name\",\"dataType\":\"varchar\"}]");
        when(tableRepo.findByFQN(fqn)).thenReturn(Optional.of(table));

        when(sourceDataSource.getConnection()).thenReturn(pgConn);
        when(pgConn.prepareStatement(anyString())).thenReturn(pgStmt);
        when(pgStmt.executeQuery()).thenReturn(pgRs);
        when(pgRs.next()).thenReturn(true, true, false);
        when(pgRs.getObject(1)).thenReturn(1, 2);
        when(pgRs.getObject(2)).thenReturn("a", "b");

        when(duckDb.getConnection()).thenReturn(duckConn);
        when(duckConn.createAppender(anyString(), eq("_raw_prod_pg_public_orders_tmp"))).thenReturn(appender);

        DuckDbSyncService service = new DuckDbSyncService(duckDb, sourceDataSource, tableRepo, profileRepo);
        SyncResult result = service.sync(fqn);

        assertNotNull(result);
        assertEquals(fqn, result.tableFQN());
        assertEquals(2, result.rowCount());

        verify(duckDb).execute("DROP TABLE IF EXISTS \"_raw_prod_pg_public_orders_tmp\"");
        verify(duckDb).execute(argThat(sql ->
                sql.startsWith("CREATE TABLE \"_raw_prod_pg_public_orders_tmp\"")
                        && sql.contains("\"id\"")
                        && sql.contains("\"name\"")));
        verify(duckDb).execute("DROP TABLE IF EXISTS \"_raw_prod_pg_public_orders\"");
        verify(duckDb).execute("ALTER TABLE \"_raw_prod_pg_public_orders_tmp\" RENAME TO \"_raw_prod_pg_public_orders\"");

        verify(appender, times(2)).beginRow();
        verify(appender, times(2)).endRow();
        verify(profileRepo).create(any(ColumnProfileEntity.class));
    }
}