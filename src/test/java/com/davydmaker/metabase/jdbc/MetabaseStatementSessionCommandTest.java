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
        assertTrue(MetabaseStatement.isSessionCommand("SET search_path = 'a;b' -- trailing ; comment"));
        assertTrue(MetabaseStatement.isSessionCommand("SET search_path = \"a;b\""));

        assertFalse(MetabaseStatement.isSessionCommand("select * from settings"));
        assertFalse(MetabaseStatement.isSessionCommand("settings"));
        assertFalse(MetabaseStatement.isSessionCommand("user_store"));
        assertFalse(MetabaseStatement.isSessionCommand("SET search_path TO public; SELECT 1"));
        assertFalse(MetabaseStatement.isSessionCommand("SET search_path TO 'public\\'; SELECT ''"));
        assertFalse(MetabaseStatement.isSessionCommand(null));
    }

    @Test
    void extractsSchemaFromSearchPath() {
        assertEquals("user_store", MetabaseStatement.extractSchemaFromSessionCommand("set search_path = \"user_store\""));
        assertEquals("a", MetabaseStatement.extractSchemaFromSessionCommand("SET SESSION search_path TO a, public;"));
        assertEquals("x", MetabaseStatement.extractSchemaFromSessionCommand("set schema 'x'"));
        assertEquals("sales,west", MetabaseStatement.extractSchemaFromSessionCommand(
            "SET search_path = \"sales,west\", public"));
        assertEquals("sales\"west", MetabaseStatement.extractSchemaFromSessionCommand(
            "SET search_path = \"sales\"\"west\", public"));
        assertEquals("public", MetabaseStatement.extractSchemaFromSessionCommand(
            "SET search_path = \"public\"; -- selected"));
        assertNull(MetabaseStatement.extractSchemaFromSessionCommand("set search_path to default"));
        assertNull(MetabaseStatement.extractSchemaFromSessionCommand("set timezone = 'UTC'"));
        assertNull(MetabaseStatement.extractSchemaFromSessionCommand("SET search_path TO public; SELECT 1"));
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
        rs.close();
        assertEquals(0, stmt.executeUpdate("SET search_path TO public"));
        assertNull(stmt.getResultSet());

        verify(connection).setSchema("user_store");
        verify(connection, times(2)).setSchema("public");
        verify(apiClient, never()).executeQuery(anyString(), anyString());
    }

    @Test
    void rejectsSessionCommandsFollowedByAnotherStatement() throws Exception {
        MetabaseApiClient apiClient = mock(MetabaseApiClient.class);
        MetabaseConnection connection = mock(MetabaseConnection.class);
        MetabaseStatement stmt = new MetabaseStatement(connection, apiClient, "db");
        String sql = "SET search_path TO public; SELECT 1";

        assertThrows(java.sql.SQLException.class, () -> stmt.execute(sql));
        assertThrows(java.sql.SQLException.class, () -> stmt.executeUpdate(sql));
        assertThrows(java.sql.SQLException.class, () -> stmt.executeQuery(sql));

        verify(connection, never()).setSchema(anyString());
        verify(apiClient, never()).executeQuery(anyString(), anyString());
    }
}
