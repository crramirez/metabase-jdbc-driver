package com.davydmaker.metabase.jdbc;

import com.davydmaker.metabase.api.MetabaseApiClient;
import com.davydmaker.metabase.api.MetabaseDatabase;
import com.davydmaker.metabase.api.MetabaseQueryResult;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;

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
        assertTrue(MetabaseStatement.isSessionCommand("SET application_name = $$a;b$$"));
        assertTrue(MetabaseStatement.isSessionCommand("SET application_name = $tag$a;b$tag$"));
        assertTrue(MetabaseStatement.isSessionCommand("SET statement_timeout = 1000"));

        assertFalse(MetabaseStatement.isSessionCommand("select * from settings"));
        assertFalse(MetabaseStatement.isSessionCommand("SET STATEMENT max_statement_time=1 FOR SELECT 1"));
        assertFalse(MetabaseStatement.isSessionCommand(
            "-- query\nset /* option */ statement /* limit */ max_statement_time=1 FOR SELECT 1;"));
        assertFalse(MetabaseStatement.isSessionCommand("settings"));
        assertFalse(MetabaseStatement.isSessionCommand("user_store"));
        assertFalse(MetabaseStatement.isSessionCommand("SET search_path TO public; SELECT 1"));
        assertFalse(MetabaseStatement.isSessionCommand("SET search_path TO 'public\\'; SELECT ''"));
        assertFalse(MetabaseStatement.isSessionCommand(null));
    }

    @Test
    void forwardsSetStatementQueriesToMetabase() throws Exception {
        String[] queries = {
            "SET STATEMENT max_statement_time=1 FOR SELECT 1",
            "-- query\nsEt /* option */ StAtEmEnT /* limit */ max_statement_time=1 FOR SELECT 1;"
        };
        for (String sql : queries) {
            MetabaseApiClient apiClient = mock(MetabaseApiClient.class);
            MetabaseConnection connection = mock(MetabaseConnection.class);
            MetabaseStatement stmt = new MetabaseStatement(connection, apiClient, "db");
            when(apiClient.executeQuery(sql, "db")).thenAnswer(invocation -> new MetabaseQueryResult(
                Collections.emptyList(), Collections.singletonList(Collections.singletonList(1))));

            assertTrue(stmt.execute(sql));
            assertEquals(-1, stmt.getUpdateCount());
            assertTrue(stmt.getResultSet().next());
            try (ResultSet rs = stmt.executeQuery(sql)) {
                assertTrue(rs.next());
                assertEquals(-1, stmt.getUpdateCount());
            }
            assertThrows(SQLFeatureNotSupportedException.class, () -> stmt.executeUpdate(sql));

            verify(apiClient, times(2)).executeQuery(sql, "db");
            verify(connection, never()).setSchema(anyString());
            stmt.close();
        }
    }

    @Test
    void recognizesBackslashEscapesByDialectAndPrefix() {
        assertTrue(MetabaseStatement.isSessionCommand("SET application_name = E'can\\'t'"));
        assertTrue(MetabaseStatement.isSessionCommand("SET application_name = e'a\\';b'"));
        assertFalse(MetabaseStatement.isSessionCommand("SET application_name = 'can\\'t'"));
        assertFalse(MetabaseStatement.isSessionCommand("SET search_path TO 'public\\'; SELECT ''", false));
        assertTrue(MetabaseStatement.isSessionCommand("SET @v = 'can\\'t'", true));
        assertTrue(MetabaseStatement.isSessionCommand("SET @v = \"a\\\";b\"", true));
    }

    @Test
    void forwardsMariaDbSetStatementWithBackslashEscapedLiteral() throws Exception {
        String sql = "SET STATEMENT max_statement_time=1 FOR SELECT 'can\\'t'";
        MetabaseApiClient apiClient = mock(MetabaseApiClient.class);
        MetabaseConnection connection = mock(MetabaseConnection.class);
        MetabaseStatement stmt = new MetabaseStatement(connection, apiClient, "db");
        when(apiClient.getDatabaseByName("db")).thenReturn(new MetabaseDatabase(1, "db", "mysql"));
        when(apiClient.executeQuery(sql, "db")).thenAnswer(invocation -> new MetabaseQueryResult(
            Collections.emptyList(), Collections.singletonList(Collections.singletonList("can't"))));

        assertTrue(stmt.execute(sql));
        try (ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
        }
        verify(apiClient, times(2)).executeQuery(sql, "db");

        MetabaseStatement postgresStmt = new MetabaseStatement(connection, mock(MetabaseApiClient.class), "db");
        assertThrows(java.sql.SQLException.class, () -> postgresStmt.execute(sql));
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
        assertEquals("public", MetabaseStatement.extractSchemaFromSessionCommand(
            "SET search_path = $$public$$"));
        assertEquals("sales,west", MetabaseStatement.extractSchemaFromSessionCommand(
            "SET search_path = $schema$sales,west$schema$, public"));
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
