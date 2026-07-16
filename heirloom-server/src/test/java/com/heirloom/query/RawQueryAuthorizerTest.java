package com.heirloom.query;

import com.heirloom.auth.UnauthorizedException;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RawQueryAuthorizerTest {
    @Mock TableRepository tableRepo;
    @InjectMocks RawQueryAuthorizer authorizer;

    @Test void shouldAllowRegisteredTableWithSelectAndLimit() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertDoesNotThrow(() -> authorizer.check("public.orders", "SELECT * FROM {table} LIMIT 10"));
    }

    @Test void shouldRejectUnregisteredTable() {
        when(tableRepo.findByFQN("public.secret")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.secret", "SELECT * FROM {table} LIMIT 10"));
    }

    @Test void shouldRejectDDL() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "DROP TABLE {table}"));
    }

    @Test void shouldRejectMultiStatement() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "SELECT * FROM {table} LIMIT 10; DROP TABLE {table}"));
    }

    @Test void shouldRejectWithoutLimit() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "SELECT * FROM {table}"));
    }

    @Test void shouldAllowWithCommentBeforeSelect() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertDoesNotThrow(() -> authorizer.check("public.orders", "-- comment\nSELECT * FROM {table} LIMIT 10"));
    }
}