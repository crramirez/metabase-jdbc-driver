package com.davydmaker.metabase.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MetabaseColumnInfoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unsignedIntegerMetadataAndValuesPreserveRange() throws Exception {
        MetabaseColumnInfo intColumn = column("int unsigned", "type/BigInteger");
        assertEquals(Types.BIGINT, intColumn.getJdbcType());
        assertEquals("java.lang.Long", intColumn.getJavaTypeName());
        assertEquals(10, intColumn.getColumnSize());
        assertEquals(0, intColumn.getDecimalDigits());
        assertInstanceOf(Long.class, result("int unsigned", "type/BigInteger", "4294967295").getValue(1));

        MetabaseColumnInfo bigintColumn = column("bigint unsigned", "type/BigInteger");
        assertEquals(Types.DECIMAL, bigintColumn.getJdbcType());
        assertEquals("java.math.BigDecimal", bigintColumn.getJavaTypeName());
        assertEquals(20, bigintColumn.getColumnSize());
        assertEquals(0, bigintColumn.getDecimalDigits());
        assertEquals(new BigDecimal("18446744073709551615"),
                result("bigint unsigned", "type/BigInteger", "18446744073709551615").getValue(1));
    }

    @Test
    void multiBitColumnsKeepTheirTextAndDeclaredWidth() throws Exception {
        MetabaseColumnInfo multiBit = column("bit(8)", "type/Text");
        assertEquals(Types.VARCHAR, multiBit.getJdbcType());
        assertEquals("java.lang.String", multiBit.getJavaTypeName());
        assertEquals(8, multiBit.getColumnSize());
        assertEquals("10101010", result("bit(8)", "type/Text", "\"10101010\"").getValue(1));

        MetabaseColumnInfo singleBit = column("bit(1)", "type/Boolean");
        assertEquals(Types.BIT, singleBit.getJdbcType());
        assertEquals("java.lang.Boolean", singleBit.getJavaTypeName());
        assertEquals(true, result("bit(1)", "type/Boolean", "\"1\"").getValue(1));
    }

    @Test
    void datetimeValuesAreParsedWithoutApplyingATimeZone() throws Exception {
        Object value = result("datetime", "type/DateTime", "\"2026-10-29T12:34:56.123\"").getValue(1);
        assertEquals(Timestamp.valueOf(LocalDateTime.of(2026, 10, 29, 12, 34, 56, 123_000_000)), value);
    }

    @Test
    void binaryMetadataMatchesJsonTextValues() {
        MetabaseColumnInfo binary = column("varbinary(16)", "type/Blob");
        assertEquals(Types.VARBINARY, binary.getJdbcType());
        assertEquals("java.lang.String", binary.getJavaTypeName());
    }

    private MetabaseColumnInfo column(String databaseType, String baseType) {
        return new MetabaseColumnInfo("value", "value", baseType, null, databaseType, null);
    }

    private MetabaseQueryResult result(String databaseType, String baseType, String value) throws Exception {
        JsonNode json = objectMapper.readTree("{\"data\":{\"cols\":[{\"name\":\"value\"," +
                "\"display_name\":\"value\",\"base_type\":\"" + baseType + "\"," +
                "\"database_type\":\"" + databaseType + "\"}],\"rows\":[[" + value + "]]}}");
        MetabaseQueryResult result = MetabaseQueryResult.fromJson(json, objectMapper);
        result.next();
        return result;
    }
}
