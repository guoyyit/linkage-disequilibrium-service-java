package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.TsvBatchParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchParserTest {

    private final TsvBatchParser parser = new TsvBatchParser();

    private static BatchImportRequest req(String id, List<String> samples,
                                          List<Variant> variants, List<List<Integer>> g) {
        return new BatchImportRequest(id, samples, variants, g, ImportOptions.defaults());
    }

    @Test
    void parsesValidInMemoryBatch() {
        CommittedBatch b = parser.parseInMemory(req("b1",
                List.of("s1", "s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(0, 1))));
        assertEquals("b1", b.batchId());
        assertEquals(2, b.samples().size());
        assertEquals(0, b.genotypes().get(0)[0]);
        assertEquals(1, b.genotypes().get(0)[1]);
    }

    @Test
    void rejectsDuplicatedSamples() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseInMemory(req("b1", List.of("s1", "s1"),
                        List.of(new Variant("chr1", 10, "A", "T")),
                        List.of(List.of(0, 1)))));
        assertTrue(ex.errors().stream().anyMatch(e -> "SAMPLE_DUPLICATED".equals(e.code())));
    }

    @Test
    void rejectsDuplicatedVariants() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseInMemory(req("b1", List.of("s1"),
                        List.of(new Variant("chr1", 10, "A", "T"),
                                new Variant("chr1", 10, "A", "T")),
                        List.of(List.of(0), List.of(2)))));
        assertTrue(ex.errors().stream().anyMatch(e -> "VARIANT_DUPLICATED".equals(e.code())));
    }

    @Test
    void rejectsWrongRowShape() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseInMemory(req("b1", List.of("s1", "s2"),
                        List.of(new Variant("chr1", 10, "A", "T")),
                        List.of(List.of(0)))));
        assertTrue(ex.errors().stream().anyMatch(e -> "ROW_SHAPE".equals(e.code())));
    }

    @Test
    void rejectsInvalidDosage() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseInMemory(req("b1", List.of("s1"),
                        List.of(new Variant("chr1", 10, "A", "T")),
                        List.of(List.of(5)))));
        assertTrue(ex.errors().stream().anyMatch(e -> "GENOTYPE_INVALID".equals(e.code())));
    }

    @Test
    void parsesTsvWithMissingAndComments() throws java.io.IOException {
                String tsv = "# comment line\n"
                + "#CHROM" + "\t" + "POS" + "\t" + "REF" + "\t" + "ALT" + "\t" + "s1" + "\t" + "s2\n"
                + "chr1" + "\t" + 10 + "\t" + "A" + "\t" + "T" + "\t" + "0/0" + "\t" + "0/1\n"
                + "chr1" + "\t" + 20 + "\t" + "G" + "\t" + "C" + "\t" + "." + "\t" + "1|1\n";
        CommittedBatch b = parser.parseTsv("b1", new StringReader(tsv));
        assertEquals(2, b.variants().size());
        assertEquals(-1, b.genotypes().get(1)[0]);
        assertEquals(2, b.genotypes().get(1)[1]);
    }

    @Test
    void rejectsTsvBadTokenWithLocation() throws java.io.IOException {
                String tsv = "#CHROM" + "\t" + "POS" + "\t" + "REF" + "\t" + "ALT" + "\t" + "s1\n"
                + "chr1" + "\t" + 10 + "\t" + "A" + "\t" + "T" + "\t" + "0/2\n";
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseTsv("b1", new StringReader(tsv)));
        assertEquals(2, ex.errors().get(0).row());
        assertEquals(5, ex.errors().get(0).column());
        assertEquals("GENOTYPE_INVALID", ex.errors().get(0).code());
    }

    @Test
    void rejectsTsvRaggedRow() throws java.io.IOException {
                String tsv = "#CHROM" + "\t" + "POS" + "\t" + "REF" + "\t" + "ALT" + "\t" + "s1" + "\t" + "s2\n"
                + "chr1" + "\t" + 10 + "\t" + "A" + "\t" + "T" + "\t" + "0/0\n";
        ValidationException ex = assertThrows(ValidationException.class,
                () -> parser.parseTsv("b1", new StringReader(tsv)));
        assertTrue(ex.errors().stream().anyMatch(e -> "ROW_SHAPE".equals(e.code())));
    }
}
