package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.GenotypeEncoding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenotypeEncodingTest {

    @Test
    void parsesExplicitDosage() {
        assertEquals(0, GenotypeEncoding.parse("0"));
        assertEquals(1, GenotypeEncoding.parse("1"));
        assertEquals(2, GenotypeEncoding.parse("2"));
    }

    @Test
    void parsesAllelePairs() {
        assertEquals(0, GenotypeEncoding.parse("0/0"));
        assertEquals(1, GenotypeEncoding.parse("0|1"));
        assertEquals(1, GenotypeEncoding.parse("1/0"));
        assertEquals(2, GenotypeEncoding.parse("1|1"));
    }

    @Test
    void parsesMissingTokens() {
        byte missing = GenotypeEncoding.MISSING;
        assertEquals(missing, GenotypeEncoding.parse(""));
        assertEquals(missing, GenotypeEncoding.parse("   "));
        assertEquals(missing, GenotypeEncoding.parse("."));
        assertEquals(missing, GenotypeEncoding.parse("NA"));
        assertEquals(missing, GenotypeEncoding.parse("?"));
        assertEquals(missing, GenotypeEncoding.parse("./."));
    }

    @Test
    void rejectsInvalidTokens() {
        assertThrows(IllegalArgumentException.class, () -> GenotypeEncoding.parse("3"));
        assertThrows(IllegalArgumentException.class, () -> GenotypeEncoding.parse("0/2"));
        assertThrows(IllegalArgumentException.class, () -> GenotypeEncoding.parse("A/B"));
        assertThrows(IllegalArgumentException.class, () -> GenotypeEncoding.parse("foo"));
    }
}
