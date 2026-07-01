package com.heirloom.cdc.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CdcPgOutputDecoder")
class CdcPgOutputDecoderTest {

    private final CdcPgOutputDecoder decoder = new CdcPgOutputDecoder();

    @Nested
    @DisplayName("relation message")
    class RelationMessage {

        @Test
        @DisplayName("registers relation and returns null (not a change event)")
        void registersRelation() {
            ByteBuffer buf = buildRelationMessage(1, "public", "customers",
                    "id", "name", "status");

            CdcEvent event = decoder.decode(buf);
            assertThat(event).isNull(); // Relation messages produce no change event
        }
    }

    @Nested
    @DisplayName("insert message")
    class InsertMessage {

        @Test
        @DisplayName("decodes insert with text values")
        void decodesInsert() {
            // First register the relation
            ByteBuffer rel = buildRelationMessage(1, "public", "customers",
                    "id", "name", "tier");
            decoder.decode(rel);

            // Then decode insert
            ByteBuffer ins = buildInsertMessage(1, "42", "Acme Corp", "gold");

            CdcEvent event = decoder.decode(ins);
            assertThat(event).isNotNull();
            assertThat(event.tableName()).isEqualTo("customers");
            assertThat(event.operation()).isEqualTo("INSERT");
            assertThat(event.newValues())
                    .containsEntry("id", "42")
                    .containsEntry("name", "Acme Corp")
                    .containsEntry("tier", "gold");
        }
    }

    @Nested
    @DisplayName("update message")
    class UpdateMessage {

        @Test
        @DisplayName("decodes update with old and new values")
        void decodesUpdate() {
            ByteBuffer rel = buildRelationMessage(1, "public", "customers",
                    "id", "name", "tier");
            decoder.decode(rel);

            ByteBuffer upd = buildUpdateMessage(1,
                    new String[]{"42", "Old Corp", "free"},
                    new String[]{"42", "Acme Corp", "enterprise"});

            CdcEvent event = decoder.decode(upd);
            assertThat(event).isNotNull();
            assertThat(event.operation()).isEqualTo("UPDATE");
            assertThat(event.oldValues())
                    .containsEntry("name", "Old Corp")
                    .containsEntry("tier", "free");
            assertThat(event.newValues())
                    .containsEntry("name", "Acme Corp")
                    .containsEntry("tier", "enterprise");
        }
    }

    @Nested
    @DisplayName("delete message")
    class DeleteMessage {

        @Test
        @DisplayName("decodes delete with old values")
        void decodesDelete() {
            ByteBuffer rel = buildRelationMessage(1, "public", "customers",
                    "id", "name", "tier");
            decoder.decode(rel);

            ByteBuffer del = buildDeleteMessage(1, "42", "Acme Corp", "gold");

            CdcEvent event = decoder.decode(del);
            assertThat(event).isNotNull();
            assertThat(event.operation()).isEqualTo("DELETE");
            assertThat(event.oldValues())
                    .containsEntry("id", "42")
                    .containsEntry("name", "Acme Corp");
        }
    }

    @Nested
    @DisplayName("unknown relation")
    class UnknownRelation {

        @Test
        @DisplayName("returns null for events on unregistered relations")
        void ignoresUnknown() {
            ByteBuffer ins = buildInsertMessage(999, "1", "Test", "x");
            CdcEvent event = decoder.decode(ins);
            assertThat(event).isNull();
        }
    }

    // --- ByteBuffer builders for pgoutput messages ---

    /**
     * Build a RELATION ('R') message.
     * Format: 'R' + relationId(4) + namespace(cstring) + tableName(cstring)
     *         + replicaIdentity(1) + columnCount(2)
     *         + per-column: flags(1) + name(cstring) + typeOid(4) + typeMod(4)
     */
    private static ByteBuffer buildRelationMessage(int relationId, String namespace,
                                                    String tableName, String... columnNames) {
        int size = 1 + 4 + cstringLen(namespace) + cstringLen(tableName) + 1 + 2;
        for (String col : columnNames) {
            size += 1 + cstringLen(col) + 4 + 4;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 'R');
        buf.putInt(relationId);
        putCString(buf, namespace);
        putCString(buf, tableName);
        buf.put((byte) 'd'); // REPLICA IDENTITY DEFAULT
        buf.putShort((short) columnNames.length);
        for (String col : columnNames) {
            buf.put((byte) 0x01); // flags
            putCString(buf, col);
            buf.putInt(25);   // typeOid (TEXT)
            buf.putInt(-1);   // typeMod
        }
        buf.flip();
        return buf;
    }

    /**
     * Build an INSERT ('I') message.
     * Format: 'I' + relationId(4) + 'N' + tuple data
     */
    private static ByteBuffer buildInsertMessage(int relationId, String... values) {
        return buildDmlMessage((byte) 'I', relationId, null, values);
    }

    private static ByteBuffer buildUpdateMessage(int relationId,
                                                   String[] oldValues, String[] newValues) {
        return buildDmlMessage((byte) 'U', relationId, oldValues, newValues);
    }

    private static ByteBuffer buildDeleteMessage(int relationId, String... oldValues) {
        return buildDmlMessage((byte) 'D', relationId, oldValues, null);
    }

    private static ByteBuffer buildDmlMessage(byte type, int relationId,
                                               String[] oldValues, String[] newValues) {
        // Use a single buffer and build everything at once
        ByteBuffer buf = ByteBuffer.allocate(4096);
        buf.put(type);
        buf.putInt(relationId);

        if (oldValues != null && type != 'I') {
            buf.put((byte) 'K');
            writeTupleValues(buf, oldValues);
        }
        if (newValues != null) {
            buf.put((byte) 'N');
            writeTupleValues(buf, newValues);
        }
        buf.flip();
        return buf;
    }

    private static void writeTupleValues(ByteBuffer buf, String[] values) {
        buf.putShort((short) values.length);
        for (String v : values) {
            if (v == null) {
                buf.put((byte) 'n');
            } else {
                buf.put((byte) 't');
                byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
                buf.putInt(bytes.length);
                buf.put(bytes);
            }
        }
    }

    private static void putCString(ByteBuffer buf, String s) {
        buf.put(s.getBytes(StandardCharsets.UTF_8));
        buf.put((byte) 0);
    }

    private static int cstringLen(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length + 1;
    }
}
