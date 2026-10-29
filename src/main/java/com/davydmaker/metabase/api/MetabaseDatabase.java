package com.davydmaker.metabase.api;

import java.util.Objects;

/**
 * Represents a connected database to Metabase.
 * 
 */
public class MetabaseDatabase {
    
    private final int id;
    private final String name;
    private final String engine;
    private final String physicalName;
    
    /**
     * Full constructor for MetabaseDatabase.
     * 
     * @param id ID of the database in Metabase
     * @param name Name of the database
     * @param engine Engine of the database (postgres, mysql, etc.)
     */
    public MetabaseDatabase(int id, String name, String engine) {
        this(id, name, engine, null);
    }
    
    /**
     * Constructor including the physical database name on the server.
     * 
     * @param id ID of the database in Metabase
     * @param name Name of the database in Metabase
     * @param engine Engine of the database (postgres, mysql, etc.)
     * @param physicalName Name of the database on the server (details.dbname), may be null
     */
    public MetabaseDatabase(int id, String name, String engine, String physicalName) {
        this.id = id;
        this.name = name;
        this.engine = engine;
        this.physicalName = physicalName;
    }
    
    /**
     * Gets the ID of the database.
     */
    public int getId() {
        return id;
    }
    
    /**
     * Gets the name of the database.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the engine of the database.
     */
    public String getEngine() {
        return engine;
    }
    
    /**
     * Gets the name of the database on the underlying server (e.g. the MySQL
     * database name), or null if Metabase did not expose it.
     */
    public String getPhysicalName() {
        return physicalName;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetabaseDatabase that = (MetabaseDatabase) o;
        return id == that.id &&
               Objects.equals(name, that.name) &&
               Objects.equals(engine, that.engine);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, name, engine);
    }
    
    @Override
    public String toString() {
        return String.format("MetabaseDatabase{id=%d, name='%s', engine='%s'}", 
                           id, name, engine);
    }
} 