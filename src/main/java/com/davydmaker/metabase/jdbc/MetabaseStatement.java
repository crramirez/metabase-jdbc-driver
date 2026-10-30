package com.davydmaker.metabase.jdbc;

import com.davydmaker.metabase.api.MetabaseApiClient;
import com.davydmaker.metabase.api.MetabaseQueryResult;
import com.davydmaker.metabase.api.MetabaseColumnInfo;
import com.davydmaker.metabase.api.MetabaseDatabase;
import com.davydmaker.metabase.api.MetabaseTableInfo;
import com.davydmaker.metabase.api.MetabaseDriverInfo;

import java.sql.*;
import java.util.*;
import java.util.LinkedHashMap;

/**
 * JDBC Statement implementation for Metabase with custom command support.
 * 
 * Provides standard JDBC Statement functionality plus custom Metabase-specific
 * commands for metadata exploration and driver information.
 */
public class MetabaseStatement implements Statement {
    protected final MetabaseConnection connection;
    protected final MetabaseApiClient apiClient;
    protected final String databaseName;
    
    // Wordle game state
    private static String wordleWord = "";
    private static String[] wordleGuesses = new String[6]; // 6 attempts
    private static boolean wordleGameActive = false;
    private static int wordleAttempts = 0;
    private static final int WORDLE_MAX_ATTEMPTS = 6;
    private static Set<String> wordleUsedWords = new HashSet<>(); // Track used words in current game
    
    // SQL-themed 5-letter words for wordle
    private static final String[] WORDLE_WORDS = {
        "ABORT", "ADMIN", "ALIAS", "ALTER", "BATCH", "BEGIN", "CACHE", "CHARS",
        "CHECK", "COUNT", "CROSS", "DATES", "DEBUG", "ERROR", "EVENT", "FALSE",
        "FETCH", "FIELD", "FLOAT", "GRANT", "GROUP", "INDEX", "INNER", "INPUT",
        "JOINS", "LIMIT", "LOCKS", "LOGIN", "MATCH", "MERGE", "NULLS", "ORDER",
        "OUTER", "PATHS", "QUERY", "RESET", "ROLES", "ROWID", "SHARE", "STORE",
        "TABLE", "TIMES", "TRANS", "TRUNC", "UNION", "USERS", "VALID", "VALUE",
        "VIEWS", "WHERE", "ARRAY", "JSONB", "REGEX", "TYPES", "VACUM", "ILIKE",
        "LEAST", "SPLIT", "UPPER", "LOWER", "LTRIM", "RTRIM", "ASCII", "ROUND",
        "FLOOR", "BTREE", "STATS", "HINTS", "SHOWS", "TRACE", "AUDIT", "FIRST",
        "USING", "EXIST"
    };
    
    protected boolean closed = false;
    protected ResultSet currentResultSet;
    protected int updateCount = -1;
    protected int maxRows = 0;
    protected int queryTimeout = 0;
    protected int fetchSize = 0;
    protected int fetchDirection = ResultSet.FETCH_FORWARD;
    
    /**
     * Creates a new Metabase statement.
     * 
     * @param connection JDBC connection
     * @param apiClient Metabase API client
     * @param databaseName Target database name
     */
    public MetabaseStatement(MetabaseConnection connection, MetabaseApiClient apiClient, String databaseName) {
        this.connection = connection;
        this.apiClient = apiClient;
        this.databaseName = databaseName;
    }
    
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        closeCurrentResultSet();
        boolean backslashEscapes = usesBackslashEscapes();
        rejectMultipleSessionCommandStatements(sql, backslashEscapes);
        
        if (isSessionCommand(sql, backslashEscapes)) {
            handleSessionCommand(sql, backslashEscapes);
            currentResultSet = new MetabaseResultSet(this,
                new MetabaseQueryResult(new ArrayList<>(), new ArrayList<>()));
            updateCount = -1;
            return currentResultSet;
        }
        
        try {
            MetabaseQueryResult result = apiClient.executeQuery(sql, databaseName);
            
            currentResultSet = new MetabaseResultSet(this, result);
            updateCount = -1;
            return currentResultSet;
            
        } catch (SQLException e) {
            
            throw e;
        }
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        closeCurrentResultSet();
        boolean backslashEscapes = usesBackslashEscapes();
        rejectMultipleSessionCommandStatements(sql, backslashEscapes);
        
        if (isSessionCommand(sql, backslashEscapes)) {
            handleSessionCommand(sql, backslashEscapes);
            updateCount = 0;
            return 0;
        }
        
        throw new SQLFeatureNotSupportedException("Metabase is read-only. Updates are not supported.");
    }
    
    @Override
    public void close() throws SQLException {
        if (!closed) {
            closeCurrentResultSet();
            closed = true;
        }
    }
    
    @Override
    public int getMaxFieldSize() throws SQLException {        
        return 0;
    }
    
    @Override
    public void setMaxFieldSize(int max) {        
    }
    
    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }
    
    @Override
    public void setMaxRows(int max) {
        this.maxRows = max;
    }
    
    @Override
    public void setEscapeProcessing(boolean enable) {
    }
    
    @Override
    public int getQueryTimeout() {
        return queryTimeout;
    }
    
    @Override
    public void setQueryTimeout(int seconds) {
        this.queryTimeout = seconds;
    }
    
    @Override
    public void cancel() throws SQLException {    
        if (apiClient != null) {
            apiClient.cancel();
        }
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
    }
    
    @Override
    public void setCursorName(String name) throws SQLException {        
    }
    
    @Override
    public boolean execute(String sql) throws SQLException {        
        closeCurrentResultSet();
        boolean backslashEscapes = usesBackslashEscapes();
        rejectMultipleSessionCommandStatements(sql, backslashEscapes);
        
        if (isCustomCommand(sql)) {
            currentResultSet = executeCustomCommand(sql);
            updateCount = -1;
            return true;
        }
        
        if (isSessionCommand(sql, backslashEscapes)) {
            handleSessionCommand(sql, backslashEscapes);
            updateCount = 0;
            return false;
        }
        
        try {
            MetabaseQueryResult result = apiClient.executeQuery(sql, databaseName);
            
            currentResultSet = new MetabaseResultSet(this, result);
            updateCount = -1;
            return true;
            
        } catch (SQLException e) {
            
            throw e;
        }
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        return currentResultSet;
    }
    
    @Override
    public int getUpdateCount() throws SQLException {
        return updateCount;
    }
    
    @Override
    public boolean getMoreResults() throws SQLException {
        closeCurrentResultSet();
        updateCount = -1;
        return false;
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD && 
            direction != ResultSet.FETCH_REVERSE && 
            direction != ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("Invalid fetch direction: " + direction);
        }
        this.fetchDirection = direction;
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return fetchDirection;
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        // Fetch size is only a hint. MySQL clients (e.g. IntelliJ/DataGrip) pass
        // Integer.MIN_VALUE to request row streaming, so negative values are ignored.
        if (rows < 0) {
            rows = 0;
        }
        this.fetchSize = rows;
    }
    
    @Override
    public int getFetchSize() throws SQLException {        
        return fetchSize;
    }
    
    @Override
    public int getResultSetConcurrency() throws SQLException {        
        return ResultSet.CONCUR_READ_ONLY;
    }
    
    @Override
    public int getResultSetType() throws SQLException {        
        return ResultSet.TYPE_FORWARD_ONLY;
    }
    
    @Override
    public void addBatch(String sql) throws SQLException {        
        throw new SQLFeatureNotSupportedException("Batch updates not supported");
    }
    
    @Override
    public void clearBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("Batch updates not supported");
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("Batch updates not supported");
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }
    
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        switch (current) {
            case Statement.CLOSE_CURRENT_RESULT:
                closeCurrentResultSet();
                break;
            case Statement.KEEP_CURRENT_RESULT:
                break;
            case Statement.CLOSE_ALL_RESULTS:
                closeCurrentResultSet();
                break;
            default:
                throw new SQLException("Invalid current value: " + current);
        }
        
        updateCount = -1;
        return false;
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {        
        throw new SQLFeatureNotSupportedException("Generated keys not supported");
    }
    
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }
    
    @Override
    public void setPoolable(boolean poolable) {
    }
    
    @Override
    public boolean isPoolable() {
        return false;
    }
    
    @Override
    public void closeOnCompletion() {
    }
    
    @Override
    public boolean isCloseOnCompletion() {
        return false;
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
    
    /**
     * Checks if statement is closed and throws exception if so.
     * 
     * @throws SQLException If statement is closed
     */
    protected void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed");
        }
    }
    
    /**
     * Closes current result set if exists.
     * 
     * @throws SQLException If close operation fails
     */
    protected void closeCurrentResultSet() throws SQLException {
        if (currentResultSet != null) {
            try {
                if (!currentResultSet.isClosed()) {
                    currentResultSet.close();
                }
            } finally {
                currentResultSet = null;
            }
        }
    }
    
    /**
     * Checks if SQL is a custom Metabase command.
     * 
     * @param sql SQL string to check
     * @return true if custom command, false otherwise
     */
    private boolean isCustomCommand(String sql) {
        if (sql == null) {
            return false;
        }
        
        String cleanSql = sql.trim().toLowerCase();
        return cleanSql.startsWith("metabase ");
    }
    
    private static final java.util.regex.Pattern SESSION_COMMAND_PATTERN = java.util.regex.Pattern.compile(
        "^(set(?!\\s+statement(?:\\s|$))|reset|use|discard)(?:\\s+.*)?$",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern SESSION_COMMAND_START_PATTERN = java.util.regex.Pattern.compile(
        "^(set|reset|use|discard)(?:\\s|;|/\\*|--|$).*",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
    
    private static final java.util.regex.Pattern SEARCH_PATH_PATTERN = java.util.regex.Pattern.compile(
        "^set\\s+(?:(?:session|local)\\s+)?(?:search_path\\s*(?:=|\\s+to\\s+)|schema\\s+)(.+?)\\s*;?$",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern DOLLAR_QUOTE_DELIMITER_PATTERN = java.util.regex.Pattern.compile(
        "\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");
    
    /**
     * Strips leading whitespace and SQL comments from a statement.
     */
    static String stripLeadingComments(String sql) {
        if (sql == null) {
            return "";
        }
        int index = 0;
        while (index < sql.length()) {
            while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
                index++;
            }
            if (sql.startsWith("--", index)) {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
                    index++;
                }
            } else if (sql.startsWith("/*", index)) {
                int depth = 1;
                index += 2;
                while (index < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", index)) {
                        depth++;
                        index += 2;
                    } else if (sql.startsWith("*/", index)) {
                        depth--;
                        index += 2;
                    } else {
                        index++;
                    }
                }
                if (depth > 0) {
                    return "";
                }
            } else {
                return sql.substring(index).trim();
            }
        }
        return "";
    }

    /**
     * MySQL/MariaDB treat backslash as an escape character inside string literals.
     */
    private boolean usesBackslashEscapes() {
        if (apiClient == null) {
            return false;
        }
        MetabaseDatabase db = apiClient.getDatabaseByName(databaseName);
        return db != null && db.getEngine() != null
            && ("mysql".equalsIgnoreCase(db.getEngine()) || "mariadb".equalsIgnoreCase(db.getEngine()));
    }

    private static boolean isEscapeStringPrefix(String sql, int quoteIndex) {
        if (quoteIndex < 1) {
            return false;
        }
        char prefix = sql.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') {
            return false;
        }
        if (quoteIndex < 2) {
            return true;
        }
        char beforePrefix = sql.charAt(quoteIndex - 2);
        return !(Character.isLetterOrDigit(beforePrefix) || beforePrefix == '_' || beforePrefix == '$');
    }

    private static String singleStatementWithoutComments(String sql, boolean backslashEscapes) {
        if (sql == null) {
            return null;
        }
        StringBuilder statement = new StringBuilder();
        int blockCommentDepth = 0;
        boolean lineComment = false;
        boolean statementEnded = false;
        char quote = 0;
        boolean quoteBackslashEscapes = false;
        String dollarQuoteDelimiter = null;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (dollarQuoteDelimiter != null) {
                if (sql.startsWith(dollarQuoteDelimiter, i)) {
                    statement.append(dollarQuoteDelimiter);
                    i += dollarQuoteDelimiter.length() - 1;
                    dollarQuoteDelimiter = null;
                } else {
                    statement.append(current);
                }
                continue;
            }
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    statement.append(' ');
                }
                continue;
            }
            if (blockCommentDepth > 0) {
                if (current == '/' && next == '*') {
                    blockCommentDepth++;
                    i++;
                } else if (current == '*' && next == '/') {
                    blockCommentDepth--;
                    i++;
                    if (blockCommentDepth == 0) {
                        statement.append(' ');
                    }
                }
                continue;
            }
            if (quote != 0) {
                statement.append(current);
                if (quoteBackslashEscapes && current == '\\' && next != 0) {
                    statement.append(next);
                    i++;
                } else if (current == quote) {
                    if (next == quote) {
                        statement.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockCommentDepth = 1;
                i++;
            } else if (current == '$' && (dollarQuoteDelimiter = dollarQuoteDelimiterAt(sql, i)) != null) {
                statement.append(dollarQuoteDelimiter);
                i += dollarQuoteDelimiter.length() - 1;
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                quoteBackslashEscapes = current != '`'
                    && (backslashEscapes || (current == '\'' && isEscapeStringPrefix(sql, i)));
                statement.append(current);
            } else if (current == '[') {
                quote = ']';
                quoteBackslashEscapes = false;
                statement.append(current);
            } else if (current == ';') {
                statementEnded = true;
            } else if (statementEnded && !Character.isWhitespace(current)) {
                return null;
            } else if (!statementEnded) {
                statement.append(current);
            }
        }
        if (quote != 0 || dollarQuoteDelimiter != null || blockCommentDepth != 0 || statement.toString().trim().isEmpty()) {
            return null;
        }
        return statement.toString().trim();
    }

    private static String dollarQuoteDelimiterAt(String sql, int index) {
        java.util.regex.Matcher matcher = DOLLAR_QUOTE_DELIMITER_PATTERN.matcher(sql);
        matcher.region(index, sql.length());
        return matcher.lookingAt() ? matcher.group() : null;
    }
    
    /**
     * Checks if SQL is a session-level statement (SET, RESET, USE, DISCARD).
     * Metabase only runs queries that return a result set and uses pooled
     * connections, so session state cannot be applied remotely. Clients such as
     * IntelliJ/DataGrip issue these statements (e.g. {@code set search_path = "x"})
     * when a schema is selected in the console, so they are handled locally.
     * MariaDB SET STATEMENT wraps a query and must be executed remotely instead.
     */
    static boolean isSessionCommand(String sql) {
        return isSessionCommand(sql, false);
    }

    static boolean isSessionCommand(String sql, boolean backslashEscapes) {
        if (sql == null) {
            return false;
        }
        String statement = singleStatementWithoutComments(sql, backslashEscapes);
        return statement != null && SESSION_COMMAND_PATTERN.matcher(statement).matches();
    }

    private static void rejectMultipleSessionCommandStatements(String sql, boolean backslashEscapes)
            throws SQLException {
        if (SESSION_COMMAND_START_PATTERN.matcher(stripLeadingComments(sql)).matches()
                && singleStatementWithoutComments(sql, backslashEscapes) == null) {
            throw new SQLException("Multiple SQL statements are not supported.");
        }
    }
    
    /**
     * Extracts the first schema from a {@code SET search_path} / {@code SET SCHEMA} statement.
     *
     * @return schema name, or null if the statement does not set a schema
     */
    static String extractSchemaFromSessionCommand(String sql) {
        return extractSchemaFromSessionCommand(sql, false);
    }

    static String extractSchemaFromSessionCommand(String sql, boolean backslashEscapes) {
        if (sql == null) {
            return null;
        }
        String statement = singleStatementWithoutComments(sql, backslashEscapes);
        if (statement == null) {
            return null;
        }
        java.util.regex.Matcher m = SEARCH_PATH_PATTERN.matcher(statement);
        if (!m.matches()) {
            return null;
        }
        String values = m.group(1).trim();
        String first;
        String dollarQuoteDelimiter = dollarQuoteDelimiterAt(values, 0);
        if (dollarQuoteDelimiter != null) {
            int closingDelimiter = values.indexOf(dollarQuoteDelimiter, dollarQuoteDelimiter.length());
            if (closingDelimiter < 0) {
                return null;
            }
            int remainder = closingDelimiter + dollarQuoteDelimiter.length();
            while (remainder < values.length() && Character.isWhitespace(values.charAt(remainder))) {
                remainder++;
            }
            if (remainder < values.length() && values.charAt(remainder) != ',') {
                return null;
            }
            first = values.substring(dollarQuoteDelimiter.length(), closingDelimiter);
        } else if (!values.isEmpty() && (values.charAt(0) == '"' || values.charAt(0) == '\'')) {
            char quote = values.charAt(0);
            StringBuilder decoded = new StringBuilder();
            int i = 1;
            boolean closed = false;
            while (i < values.length()) {
                char current = values.charAt(i);
                if (current == quote) {
                    if (i + 1 < values.length() && values.charAt(i + 1) == quote) {
                        decoded.append(quote);
                        i += 2;
                    } else {
                        i++;
                        closed = true;
                        break;
                    }
                } else {
                    decoded.append(current);
                    i++;
                }
            }
            while (i < values.length() && Character.isWhitespace(values.charAt(i))) {
                i++;
            }
            if (!closed || (i < values.length() && values.charAt(i) != ',')) {
                return null;
            }
            first = decoded.toString();
        } else {
            int comma = values.indexOf(',');
            int whitespace = -1;
            for (int i = 0; i < values.length(); i++) {
                if (Character.isWhitespace(values.charAt(i))) {
                    whitespace = i;
                    break;
                }
            }
            int end = comma < 0 ? values.length() : comma;
            if (whitespace >= 0 && whitespace < end) {
                end = whitespace;
            }
            first = values.substring(0, end).trim();
        }
        if (first.isEmpty() || first.equalsIgnoreCase("default")) {
            return null;
        }
        return first;
    }
    
    private void handleSessionCommand(String sql, boolean backslashEscapes) throws SQLException {
        String schema = extractSchemaFromSessionCommand(sql, backslashEscapes);
        if (schema != null) {
            connection.setSchema(schema);
        }
    }
    
    /**
     * Executes custom Metabase command and returns result set.
     * 
     * @param sql Custom command SQL
     * @return Command result as ResultSet
     * @throws SQLException If command execution fails
     */
    private ResultSet executeCustomCommand(String sql) throws SQLException {
        String cleanSql = sql.trim().toLowerCase();
        
        return executeMetabaseCommand(cleanSql.substring(9).trim());
    }
    
    /**
     * Executes metabase-prefixed commands.
     * 
     * @param subCommand Command without 'metabase' prefix
     * @return Command result as ResultSet
     * @throws SQLException If command execution fails
     */
    private ResultSet executeMetabaseCommand(String subCommand) throws SQLException {
        String[] parts = subCommand.split("\\s+");
        
        if (parts.length == 0) {
            throw new SQLException("Metabase command incomplete");
        }
        
        String command = parts[0].toLowerCase();
        
        switch (command) {
            case "help":
                return executeMetabaseHelpCommand();
            case "databases":
                return executeMetabaseDatabasesCommand();
            case "status":
                return executeMetabaseStatusCommand();
            case "info":
                return executeMetabaseInfoCommand();
            case "schemas":
                return executeMetabaseSchemasCommand();
            case "tables":
                if (parts.length > 1) {
                    return executeMetabaseTablesCommand(parts[1]);
                } else {
                    throw new SQLException("Metabase command 'tables' requires a schema. Usage: metabase tables <schema>");
                }
            case "describe":
                if (parts.length > 1) {
                    return executeMetabaseDescribeCommand(parts[1]);
                } else {
                    throw new SQLException("Metabase command 'describe' requires a table. Usage: metabase describe <schema>.<table>");
                }
            case "wordle":
                if (parts.length > 1) {
                    return executeMetabaseWordleCommand(parts[1]);
                } else {
                    if (wordleGameActive) {
                        return executeMetabaseWordleCommand("status");
                    } else {
                        return executeMetabaseWordleCommand("help");
                    }
                }
            default:
                throw new SQLException("Metabase command not recognized: " + command + 
                                     ". Use 'metabase help' to see all available commands.");
        }
    }
    
    /**
     * Executes "metabase status" command showing connection status.
     * 
     * @return Status information as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseStatusCommand() throws SQLException {
        
        List<MetabaseColumnInfo> columns = new ArrayList<>();
        columns.add(new MetabaseColumnInfo("property", "property", "type/Text", null));
        columns.add(new MetabaseColumnInfo("value", "value", "type/Text", null));
        
        List<List<Object>> rows = new ArrayList<>();
        
        rows.add(List.of("driver_version", MetabaseDriverInfo.DRIVER_VERSION));
        rows.add(List.of("connected_database", databaseName));
        rows.add(List.of("authenticated", apiClient.isAuthenticated() ? "true" : "false"));
        rows.add(List.of("api_base_url", apiClient.getBaseUrl()));
        
        try {
            // Cache information is now managed by the global cache system
            rows.add(List.of("cache_status", "Global cache active"));
            rows.add(List.of("cache_age_seconds", "N/A - Global cache"));
        } catch (Exception e) {
            rows.add(List.of("cache_status", "Error: " + e.getMessage()));
        }
        
        MetabaseQueryResult result = new MetabaseQueryResult(columns, rows);
        
        return new MetabaseResultSet(this, result);
    }
    
    /**
     * Executes "metabase info" command showing technical information.
     * 
     * @return Technical information as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseInfoCommand() throws SQLException {
        List<MetabaseColumnInfo> columns = new ArrayList<>();
        columns.add(new MetabaseColumnInfo("component", "component", "type/Text", null));
        columns.add(new MetabaseColumnInfo("details", "details", "type/Text", null));
        
        List<List<Object>> rows = new ArrayList<>();
        
        rows.add(List.of("JDBC Driver", MetabaseDriverInfo.DRIVER_NAME + " v" + 
                        MetabaseDriverInfo.DRIVER_VERSION));
        rows.add(List.of("Database Connected", databaseName));
        
        try {
            MetabaseDatabase currentDb = apiClient.getDatabaseByName(databaseName);
            if (currentDb != null) {
                rows.add(List.of("Database Engine", currentDb.getEngine()));
                rows.add(List.of("Database ID", String.valueOf(currentDb.getId())));
            }
            
            List<String> schemas = apiClient.getSchemas();
            rows.add(List.of("Schemas Available", String.valueOf(schemas.size())));
            
        } catch (Exception e) {
            rows.add(List.of("Database Info", "Error loading: " + e.getMessage()));
        }
        
        MetabaseQueryResult result = new MetabaseQueryResult(columns, rows);
        
        return new MetabaseResultSet(this, result);
    }
    
    /**
     * Executes "metabase schemas" command listing available schemas.
     * 
     * @return Schema list as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseSchemasCommand() throws SQLException {
        try {
            List<String> schemas = apiClient.getSchemas();
            
            List<MetabaseColumnInfo> columns = new ArrayList<>();
            columns.add(new MetabaseColumnInfo("schema_name", "schema_name", "type/Text", null));
            columns.add(new MetabaseColumnInfo("table_count", "table_count", "type/Integer", null));
            
            List<List<Object>> rows = new ArrayList<>();
            for (String schema : schemas) {
                try {
                    List<MetabaseTableInfo> tables = apiClient.getTables(schema);
                    rows.add(List.of(schema, tables.size()));
                } catch (Exception e) {
                    rows.add(List.of(schema, "Error: " + e.getMessage()));
                }
            }
            
            MetabaseQueryResult result = 
                new MetabaseQueryResult(columns, rows);
            
            return new MetabaseResultSet(this, result);
        } catch (Exception e) {
            throw new SQLException("Error listing schemas: " + e.getMessage(), e);
        }
    }
    
    /**
     * Executes "metabase tables <schema>" command listing schema tables.
     * 
     * @param schemaName Target schema name
     * @return Table list as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseTablesCommand(String schemaName) throws SQLException {
        try {
            List<MetabaseTableInfo> tables = apiClient.getTables(schemaName);
            
            List<MetabaseColumnInfo> columns = new ArrayList<>();
            columns.add(new MetabaseColumnInfo("table_name", "table_name", "type/Text", null));
            columns.add(new MetabaseColumnInfo("entity_type", "entity_type", "type/Text", null));
            
            List<List<Object>> rows = new ArrayList<>();
            for (MetabaseTableInfo table : tables) {
                List<Object> row = new ArrayList<>();
                row.add(table.getName());
                row.add(table.getEntityType() != null ? table.getEntityType() : "table");
                row.add(table.getDescription());
                row.add(table.getEstimatedRowCount());
                rows.add(row);
            }
            
            MetabaseQueryResult result = 
                new MetabaseQueryResult(columns, rows);
            
            return new MetabaseResultSet(this, result);
        } catch (Exception e) {
            throw new SQLException("Error listing tables for schema '" + schemaName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Executes "metabase describe" command showing detailed table structure.
     * 
     * @param tableName Qualified table name (schema.table)
     * @return Table structure as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseDescribeCommand(String tableName) throws SQLException {
        try {
            String[] parts = tableName.split("\\.");
            if (parts.length != 2) {
                throw new SQLException("Invalid table format. Use: schema.table");
            }
            
            String schemaName = parts[0];
            String tableNameOnly = parts[1];
            
            String describeQuery = 
                "SELECT " +
                "    f.attnum AS \"order\", " +
                "    f.attname AS name, " +
                "    f.attnotnull AS \"notNull\", " +
                "    pg_catalog.format_type(f.atttypid,f.atttypmod) AS type, " +
                "    CASE " +
                "        WHEN p.contype = 'p' THEN true " +
                "        ELSE false " +
                "    END AS primarykey, " +
                "    CASE " +
                "        WHEN p.contype = 'u' THEN true " +
                "        ELSE false " +
                "    END AS uniquekey, " +
                "    CASE " +
                "        WHEN f.atthasdef = 't' THEN pg_get_expr(d.adbin, d.adrelid) " +
                "    END AS default " +
                "FROM pg_attribute f " +
                "    JOIN pg_class c ON c.oid = f.attrelid " +
                "    JOIN pg_type t ON t.oid = f.atttypid " +
                "    LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = f.attnum " +
                "    LEFT JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "    LEFT JOIN pg_constraint p ON p.conrelid = c.oid AND f.attnum = ANY (p.conkey) " +
                "    LEFT JOIN pg_class AS g ON p.confrelid = g.oid " +
                "WHERE c.relkind = 'r'::char " +
                "    AND n.nspname = '" + schemaName + "' " +
                "    AND c.relname = '" + tableNameOnly + "' " +
                "    AND f.attnum > 0 " +
                "    AND g.relname is null " +
                "ORDER BY f.attnum";

            MetabaseQueryResult queryResult = apiClient.executeQuery(describeQuery, databaseName);
            
            Map<String, ColumnDescribeInfo> columnsMap = new LinkedHashMap<>();
            
            for (List<Object> row : queryResult.getRows()) {
                String columnName = (String) row.get(1);
                
                ColumnDescribeInfo colInfo = columnsMap.computeIfAbsent(columnName, k -> new ColumnDescribeInfo());
                
                if (colInfo.order == null) {
                    colInfo.order = row.get(0) != null ? ((Number) row.get(0)).intValue() : 0;
                    colInfo.name = columnName;
                    colInfo.notNull = row.get(2) != null ? (Boolean) row.get(2) : false;
                    colInfo.type = (String) row.get(3);
                    colInfo.defaultValue = (String) row.get(6);
                }
                
                Boolean isPrimary = row.get(4) != null ? (Boolean) row.get(4) : false;
                Boolean isUnique = row.get(5) != null ? (Boolean) row.get(5) : false;
                
                if (isPrimary) colInfo.isPrimaryKey = true;
                if (isUnique) colInfo.isUniqueKey = true;
            }
            
            List<MetabaseColumnInfo> columns = new ArrayList<>();
            columns.add(new MetabaseColumnInfo("order", "Order", "type/Integer", null));
            columns.add(new MetabaseColumnInfo("name", "Name", "type/Text", null));
            columns.add(new MetabaseColumnInfo("notnull", "Not Null", "type/Boolean", null));
            columns.add(new MetabaseColumnInfo("type", "Type", "type/Text", null));
            columns.add(new MetabaseColumnInfo("primarykey", "Primary Key", "type/Boolean", null));
            columns.add(new MetabaseColumnInfo("uniquekey", "Unique Key", "type/Boolean", null));
            columns.add(new MetabaseColumnInfo("default", "Default", "type/Text", null));

            List<List<Object>> rows = new ArrayList<>();
            
            columnsMap.values().stream()
                .sorted((a, b) -> Integer.compare(a.order, b.order))
                .forEach(colInfo -> {
                    List<Object> row = new ArrayList<>();

                    row.add(colInfo.order);
                    row.add(colInfo.name);
                    row.add(colInfo.notNull);
                    row.add(colInfo.type);
                    row.add(colInfo.isPrimaryKey);
                    row.add(colInfo.isUniqueKey);
                    row.add(colInfo.defaultValue);
                    
                    rows.add(row);
                });

            MetabaseQueryResult result = 
                new MetabaseQueryResult(columns, rows);

            return new MetabaseResultSet(this, result);
        } catch (Exception e) {
            throw new SQLException("\n" + e.getMessage(), e);
        }
    }
    
    /**
     * Helper class for aggregating column description information.
     */
    private static class ColumnDescribeInfo {
        Integer order;
        String name;
        Boolean notNull;
        String type;
        String defaultValue;
        Boolean isPrimaryKey = false;
        Boolean isUniqueKey = false;
    }

    /**
     * Executes "metabase help" command showing available commands.
     * 
     * @return Help information as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseHelpCommand() throws SQLException {
        List<MetabaseColumnInfo> columns = new ArrayList<>();
        columns.add(new MetabaseColumnInfo("command", "Command", "type/Text", null));
        columns.add(new MetabaseColumnInfo("description", "Description", "type/Text", null));
        
        List<List<Object>> rows = new ArrayList<>();
        
        rows.add(List.of("metabase help", "Shows this list of available commands"));
        rows.add(List.of("metabase databases", "Lists all Metabase databases"));
        rows.add(List.of("metabase status", "Shows connection and authentication status"));
        rows.add(List.of("metabase info", "Shows technical driver and database information"));
        rows.add(List.of("metabase schemas", "Lists available schemas with table counts"));
        rows.add(List.of("metabase tables <schema>", "Lists tables in specified schema"));
        rows.add(List.of("metabase describe <schema>.<table>", "Shows table structure (Oracle-style)"));
        rows.add(List.of("metabase wordle <cmd>", "🎮🔤 SQL Wordle game! Commands: start|help|status|words|<guess>"));
        
        MetabaseQueryResult result = new MetabaseQueryResult(columns, rows);
        
        return new MetabaseResultSet(this, result);
    }
    
    /**
     * Executes "metabase databases" command listing Metabase databases.
     * 
     * @return Database list as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseDatabasesCommand() throws SQLException {
        try {
            List<MetabaseDatabase> databases = apiClient.getAllDatabases();
            
            List<MetabaseColumnInfo> columns = new ArrayList<>();
            columns.add(new MetabaseColumnInfo("id", "id", "type/Integer", null));
            columns.add(new MetabaseColumnInfo("name", "name", "type/Text", null));
            columns.add(new MetabaseColumnInfo("engine", "engine", "type/Text", null));
            
            List<List<Object>> rows = new ArrayList<>();
            for (MetabaseDatabase db : databases) {
                List<Object> row = new ArrayList<>();
                row.add(db.getId());
                row.add(db.getName());
                row.add(db.getEngine());
                rows.add(row);
            }
            
            MetabaseQueryResult result = new MetabaseQueryResult(columns, rows);
            
            return new MetabaseResultSet(this, result);
        } catch (Exception e) {
            throw new SQLException("Error listing databases: " + e.getMessage(), e);
        }
    }
    
    /**
     * Executes "metabase wordle" command - ASCII SQL Wordle game!
     * 
     * @param input 5-letter word guess or command (start, help)
     * @return Game state as ResultSet
     * @throws SQLException If execution fails
     */
    private ResultSet executeMetabaseWordleCommand(String input) throws SQLException {
        List<MetabaseColumnInfo> columns = new ArrayList<>();
        columns.add(new MetabaseColumnInfo("field", "Field", "type/Text", null));
        columns.add(new MetabaseColumnInfo("value", "Value", "type/Text", null));
        
        List<List<Object>> rows = new ArrayList<>();
        
        String wordleGrid = "";
        String keyboard = "";
        String status = "";
        String progress = "";
        
        input = input.toUpperCase().trim();
        
        if ("START".equals(input) || "RESET".equals(input)) {
            initializeWordleGame();
            wordleGrid = getWordleGrid();
            keyboard = getWordleKeyboard();
            status = "🎮 NEW SQL WORDLE! Guess the 5-letter SQL word in 6 tries!";
            progress = getWordleProgress();
        }
        else if ("HELP".equals(input)) {
            wordleGrid = getWordleHelpGrid();
            keyboard = getWordleKeyboard();
            status = "📋 HOW TO PLAY: Guess 5-letter SQL words to find the target!";
            progress = "🟩=Correct 🟨=Wrong Position ⬛=Not in word";
        }
        else if ("STATUS".equals(input)) {
            if (!wordleGameActive) {
                wordleGrid = getWordleStatusGrid();
                keyboard = getWordleKeyboard();
                status = "📊 No active game. Use 'metabase wordle start' to begin!";
                progress = "Game Status: Not Started";
            } else {
                wordleGrid = getWordleGrid();
                keyboard = getWordleKeyboard();
                status = String.format("📊 Game in progress: %d/%d attempts used", wordleAttempts, WORDLE_MAX_ATTEMPTS);
                progress = getWordleProgress();
            }
        }
        else if ("WORDS".equals(input)) {
            wordleGrid = getWordleWordsGrid();
            keyboard = getWordleKeyboard();
            status = String.format("📚 Dictionary contains %d SQL words", WORDLE_WORDS.length);
            progress = "All valid 5-letter SQL words for Wordle";
        }
        else if (input.matches("[A-Z]{5}")) {
            if (!wordleGameActive) {
                wordleGrid = getWordleGrid();
                keyboard = getWordleKeyboard();
                status = "❌ Game not started! Use 'metabase wordle start' first";
                progress = "Use: metabase wordle start";
            }
            else if (wordleAttempts >= WORDLE_MAX_ATTEMPTS) {
                wordleGrid = getWordleGrid();
                keyboard = getWordleKeyboard();
                status = "❌ Game already finished! Use 'metabase wordle start' for new game";
                progress = getWordleProgress();
            }
            else if (!isValidWordleDictionaryWord(input)) {
                wordleGrid = getWordleGrid();
                keyboard = getWordleKeyboard();
                status = "❌ '" + input + "' is not in the SQL dictionary! Try another word.";
                progress = "Only valid SQL words are accepted.";
            }
            else if (wordleUsedWords.contains(input)) {
                wordleGrid = getWordleGrid();
                keyboard = getWordleKeyboard();
                status = "❌ '" + input + "' was already used in this game! Try a different word.";
                progress = "Choose a word you haven't tried yet.";
            }
            else {
                try {
                    makeWordleGuess(input);
                    wordleGrid = getWordleGrid();
                    keyboard = getWordleKeyboard();
                    
                    String gameResult = checkWordleGameEnd();
                    if (gameResult != null) {
                        status = gameResult;
                        progress = "Game Over! Use: metabase wordle start (new game)";
                        wordleGameActive = false;
                    } else {
                        status = String.format("Attempt %d/%d: %s", wordleAttempts, WORDLE_MAX_ATTEMPTS, getWordleHint(input));
                        progress = getWordleProgress();
                    }
                } catch (IllegalArgumentException e) {
                    wordleGrid = getWordleGrid();
                    keyboard = getWordleKeyboard();
                    status = "❌ " + e.getMessage();
                    progress = "Try a different word.";
                }
            }
        }
        else {
            wordleGrid = getWordleGrid();
            keyboard = getWordleKeyboard();
            status = "❌ Invalid input! Use: start, help, status, words, or 5-letter SQL words";
            progress = "Commands: start | help | status | words | <5-letter-word>";
        }
        
        rows.add(List.of("Wordle Grid", wordleGrid));
        rows.add(List.of("Keyboard", keyboard));
        rows.add(List.of("Status", status));
        rows.add(List.of("Progress", progress));
        
        MetabaseQueryResult result = new MetabaseQueryResult(columns, rows);
        return new MetabaseResultSet(this, result);
    }
    
    private void initializeWordleGame() {
        int randomIndex = (int) (Math.random() * WORDLE_WORDS.length);
        wordleWord = WORDLE_WORDS[randomIndex];
        
        for (int i = 0; i < WORDLE_MAX_ATTEMPTS; i++) {
            wordleGuesses[i] = "";
        }
        
        wordleGameActive = true;
        wordleAttempts = 0;
        wordleUsedWords.clear();
    }
    
    private void makeWordleGuess(String guess) {
        if (wordleUsedWords.contains(guess)) {
            throw new IllegalArgumentException("Word '" + guess + "' has already been used in this game.");
        }
        wordleUsedWords.add(guess);
        wordleGuesses[wordleAttempts] = guess;
        wordleAttempts++;
    }
    
    private String checkWordleGameEnd() {
        if (wordleAttempts > 0 && wordleWord.equals(wordleGuesses[wordleAttempts - 1])) {
            return String.format("🏆 EXCELLENT! You found the SQL word: %s in %d attempts! 🎉", 
                                wordleWord, wordleAttempts);
        }
        
        if (wordleAttempts >= WORDLE_MAX_ATTEMPTS) {
            return String.format("💀 QUERY FAILED! The SQL word was: %s 💾", wordleWord);
        }
        
        return null;
    }
    
    private String getWordleHint(String guess) {
        if (wordleWord.equals(guess)) {
            return "Perfect match! 🎯";
        }
        
        int correctPositions = 0;
        int correctLetters = 0;
        
        for (int i = 0; i < 5; i++) {
            if (wordleWord.charAt(i) == guess.charAt(i)) {
                correctPositions++;
            }
        }
        
        for (char c : guess.toCharArray()) {
            if (wordleWord.indexOf(c) != -1) {
                correctLetters++;
            }
        }
        
        return String.format("%d correct positions, %d correct letters", correctPositions, correctLetters - correctPositions);
    }
    
    private String getWordleProgress() {
        return String.format("Attempt: %d/%d | Target: %s-letter SQL word | Remaining: %d", 
                           wordleAttempts, WORDLE_MAX_ATTEMPTS, wordleWord.length(), 
                           WORDLE_MAX_ATTEMPTS - wordleAttempts);
    }
    
    private String getWordleGrid() {
        StringBuilder grid = new StringBuilder();
        
        grid.append("╔══════════════════════════════════════════════════════╗\n");
        grid.append("║                🎮🔤 SQL WORDLE 🔤🎮                 ║\n");
        grid.append("╚══════════════════════════════════════════════════════╝\n");
        grid.append("\n");
        
        for (int i = 0; i < WORDLE_MAX_ATTEMPTS; i++) {
            String rowContent = "";
            
            if (i < wordleAttempts && !wordleGuesses[i].isEmpty()) {
                rowContent = getColoredGuess(wordleGuesses[i]);
            } else if (i == wordleAttempts && wordleGameActive) {
                rowContent = "      [ _ _ _ _ _ ] ← Type here";
            } else {
                rowContent = "      [ _ _ _ _ _ ]";
            }
            
            grid.append(rowContent).append("\n");
        }
        
        grid.append("\n");
        grid.append("╔══════════════════════════════════════════════════════╗\n");
        grid.append("║              🎯 Find the 5-letter SQL word! 🎯      ║\n");
        grid.append("╚══════════════════════════════════════════════════════╝");
        
        return grid.toString();
    }
    
    private String padToWidth(String text, int width) {
        if (text == null) text = "";
        
        int displayLength = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x1F000) {
                displayLength += 2;
            } else {
                displayLength += 1;
            }
        }
        
        if (displayLength >= width) {
            return text.substring(0, Math.min(text.length(), width));
        } else {
            StringBuilder padded = new StringBuilder(text);
            for (int i = displayLength; i < width; i++) {
                padded.append(" ");
            }
            return padded.toString();
        }
    }
    
    private String getColoredGuess(String guess) {
        StringBuilder colored = new StringBuilder("      [ ");
        
        for (int i = 0; i < 5; i++) {
            char guessChar = guess.charAt(i);
            char targetChar = wordleWord.charAt(i);
            
            if (guessChar == targetChar) {
                colored.append("🟩");
            } else if (wordleWord.indexOf(guessChar) != -1) {
                colored.append("🟨");
            } else {
                colored.append("⬛");
            }
        }
        
        colored.append(" ] ").append(guess);
        
        return colored.toString();
    }
    
    private String getWordleKeyboard() {
        return 
            "╔════════════════ KEYBOARD LEGEND ════════════════╗\n" +
            "║                                                  ║\n" +
            "║   🟩 Green  = Correct letter, correct position  ║\n" +
            "║   🟨 Yellow = Correct letter, wrong position    ║\n" +
            "║   ⬛ Black  = Letter not in the word           ║\n" +
            "║                                                  ║\n" +
            "║         SQL WORDS: TABLE, INDEX, WHERE,         ║\n" +
            "║         ORDER, GROUP, UNION, ALTER, etc.        ║\n" +
            "║                                                  ║\n" +
            "╚══════════════════════════════════════════════════╝";
    }
    
    private String getWordleHelpGrid() {
        return 
            "╔══════════════════════════════════════════════════════╗\n" +
            "║                🎮🔤 SQL WORDLE 🔤🎮                 ║\n" +
            "║                                                      ║\n" +
            "║                   📋 HOW TO PLAY:                   ║\n" +
            "║                                                      ║\n" +
            "║          • Guess the 5-letter SQL word              ║\n" +
            "║          • You have 6 attempts maximum              ║\n" +
            "║          • Only valid SQL dictionary words accepted ║\n" +
            "║          • No duplicate words per game              ║\n" +
            "║          • After each guess, get color feedback:    ║\n" +
            "║                                                      ║\n" +
            "║            🟩 = Right letter, right spot            ║\n" +
            "║            🟨 = Right letter, wrong spot            ║\n" +
            "║            ⬛ = Letter not in word                 ║\n" +
            "║                                                      ║\n" +
            "║                   📋 COMMANDS:                      ║\n" +
            "║                                                      ║\n" +
            "║          start  - Start new game                    ║\n" +
            "║          help   - Show this help                    ║\n" +
            "║          status - Show current game status          ║\n" +
            "║          words  - Show all available words          ║\n" +
            "║          <word> - Make a 5-letter guess             ║\n" +
            "║                                                      ║\n" +
            "║            Try: metabase wordle TABLE               ║\n" +
            "║                                                      ║\n" +
            "╚══════════════════════════════════════════════════════╝";
    }

    private String getWordleStatusGrid() {
        StringBuilder status = new StringBuilder();
        
        status.append("╔══════════════════════════════════════════════════════╗\n");
        status.append("║                📊 WORDLE STATUS 📊                  ║\n");
        status.append("╚══════════════════════════════════════════════════════╝\n");
        status.append("\n");
        
        if (!wordleGameActive) {
            status.append("      🚫 No active game\n");
            status.append("      Use 'metabase wordle start' to begin!\n\n");
        } else {
            status.append(String.format("      🎯 Target word: %d letters\n", wordleWord.length()));
            status.append(String.format("      📈 Attempts used: %d/%d\n", wordleAttempts, WORDLE_MAX_ATTEMPTS));
            status.append(String.format("      🔄 Remaining attempts: %d\n", WORDLE_MAX_ATTEMPTS - wordleAttempts));
            status.append("\n");
            
            if (!wordleUsedWords.isEmpty()) {
                status.append("      📝 Words tried this game:\n");
                String[] usedArray = wordleUsedWords.toArray(new String[0]);
                for (int i = 0; i < usedArray.length; i++) {
                    if (i % 5 == 0 && i > 0) status.append("\n");
                    if (i % 5 == 0) status.append("         ");
                    status.append(String.format("%-8s", usedArray[i]));
                }
                status.append("\n\n");
            }
            
            status.append("      🎮 Game in progress...\n\n");
        }
        
        status.append("╔══════════════════════════════════════════════════════╗\n");
        status.append("║           Use 'metabase wordle help' for rules      ║\n");
        status.append("╚══════════════════════════════════════════════════════╝");
        
        return status.toString();
    }

    private String getWordleWordsGrid() {
        StringBuilder words = new StringBuilder();
        
        words.append("╔══════════════════════════════════════════════════════╗\n");
        words.append("║               📚 SQL WORDLE DICTIONARY 📚           ║\n");
        words.append("╚══════════════════════════════════════════════════════╝\n");
        words.append("\n");
        
        words.append(String.format("      Total words available: %d\n\n", WORDLE_WORDS.length));
        
        for (int i = 0; i < WORDLE_WORDS.length; i++) {
            if (i % 6 == 0) {
                words.append("      ");
            }
            
            String word = WORDLE_WORDS[i];
            if (wordleUsedWords.contains(word)) {
                words.append(String.format("✓%-8s", word));
            } else {
                words.append(String.format("%-9s", word));
            }
            
            if ((i + 1) % 6 == 0 || i == WORDLE_WORDS.length - 1) {
                words.append("\n");
            }
        }
        
        words.append("\n");
        if (!wordleUsedWords.isEmpty()) {
            words.append("      ✓ = Already used in current game\n\n");
        }
        
        words.append("╔══════════════════════════════════════════════════════╗\n");
        words.append("║          All words are valid 5-letter SQL terms     ║\n");
        words.append("╚══════════════════════════════════════════════════════╝");
        
        return words.toString();
    }

    /**
     * Checks if a given word is a valid SQL dictionary word.
     * 
     * @param word The word to check
     * @return true if the word is in the SQL dictionary, false otherwise
     */
    private boolean isValidWordleDictionaryWord(String word) {
        for (String dictWord : WORDLE_WORDS) {
            if (dictWord.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }
} 