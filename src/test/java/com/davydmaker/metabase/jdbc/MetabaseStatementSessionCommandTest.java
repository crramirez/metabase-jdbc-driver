package com.davydmaker.metabase.jdbc;

import com.davydmaker.metabase.api.MetabaseApiClient;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MetabaseStatementSessionCommandTest {

    @Test
    void detectsSessionCommands() {
        assertTrue(MetabaseStatement.isSessionCommand("set search_path = \"user_store\""));
        assertTrue(MetabaseStatement.isSessionCommand("  SET search_path TO public;"));
        assertTrue(MetabaseStatement.isSessionCommand("/* comment */ set schema 'x'"));
        assertTrue(MetabaseStatement.isSessionCommand("-- c\nUSE mydb"));
        assertTrue(MetabaseStatement.isSessionCommand("RESET ALL"));
        assertTrue(MetabaseStatement.isSessionCommand("discard all"));

        assertFalse(MetabaseStatement.isSessionCommand("select * from settings"));
        assertFalse(MetabaseStatement.isSessionCommand("settings"));
        assertFalse(MetabaseStatement.isSessionCommand("user_store"));
        assertFalse(MetabaseStatement.isSessionCommand(null));
    }

    @Test
    void extractsSchemaFromSearchPath() {
        assertEquals("user_store", MetabaseStatement.extractSchemaFromSessionCommand("set search_path = \"user_store\""));
        assertEquals("a", MetabaseStatement.extractSchemaFromSessionCommand("SET SESSION search_path TO a, public;"));
        assertEquals("x", MetabaseStatement.extractSchemaFromSessionCommand("set schema 'x'"));
        assertNull(MetabaseStatement.extractSchemaFromSessionCommand("set search_path to default"));
        assertNull(MetabaseStatement.extractSchemaFromSessionCommand("set timezone = 'UTC'"));
    }

    @Test
    void executeHandlesSetLocallyWithoutCallingMetabase() throws Exception {
        MetabaseApiClient apiClient = mock(MetabaseApiClient.class);
        MetabaseConnection connection = mock(MetabaseConnection.class);
        MetabaseStatement stmt = new MetabaseStatement(connection, apiClient, "db");

        assertFalse(stmt.execute("set search_path = \"user_store\""));
        assertEquals(0, stmt.getUpdateCount());
        assertNull(stmt.getResultSet());
        assertEquals(0, stmt.executeUpdate("SET search_path TO public"));
        ResultSet rs = stmt.executeQuery("set timezone = 'UTC'");
        assertFalse(rs.next());

        verify(connection).setSchema("user_store");
        verify(connection).setSchema("public");
        verify(apiClient, never()).executeQuery(anyString(), anyString());
    }
}
