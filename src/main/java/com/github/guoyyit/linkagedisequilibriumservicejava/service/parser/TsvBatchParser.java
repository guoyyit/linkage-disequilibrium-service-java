package com.github.guoyyit.linkagedisequilibriumservicejava.service.parser;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ErrorLocation;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses batches from in-memory requests or tab separated files.
 *
 * <p>TSV layout:
 * <pre>
 * #CHROM  POS  REF  ALT  sampleA  sampleB ...
 * chr1     100  A    T    0/0      0/1
 * </pre>
 * Lines starting with {@code #} are ignored; the first non-comment line is
 * the header and must contain at least 5 tab separated columns.
 * Only structural integrity is checked here; import filtering rules live in
 * the validator.
 */
@Component
public class TsvBatchParser implements BatchParser {

    private static final int FIXED_COLUMNS = 4;

    @Override
    public CommittedBatch parseInMemory(BatchImportRequest request) {
        List<ErrorLocation> errors = new ArrayList<>();
        String batchId = request.batchId();
        if (batchId == null || batchId.isBlank()) {
            errors.add(ErrorLocation.of(null, "BATCH_ID_MISSING", "batchId must not be blank"));
        }
        List<String> samples = request.samples() == null ? List.of() : request.samples();
        List<Variant> variants = request.variants() == null ? List.of() : request.variants();
        List<List<Integer>> rows = request.genotypes() == null ? List.of() : request.genotypes();

        checkShape(batchId, samples, variants, rows, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        Set<String> sampleSet = new HashSet<>();
        for (int c = 0; c < samples.size(); c++) {
            String s = samples.get(c);
            if (s == null || s.isBlank()) {
                errors.add(new ErrorLocation(batchId, null, c + 1, null, null,
                        "SAMPLE_ID_MISSING", "sample id is blank at column " + (c + 1)));
            } else if (!sampleSet.add(s)) {
                errors.add(new ErrorLocation(batchId, null, c + 1, s, null,
                        "SAMPLE_DUPLICATED", "duplicated sample: " + s));
            }
        }
        Set<Variant> variantSet = new HashSet<>();
        for (int r = 0; r < variants.size(); r++) {
            Variant v = variants.get(r);
            if (v == null) {
                errors.add(new ErrorLocation(batchId, r + 1, null, null, null,
                        "VARIANT_MISSING", "variant is null at row " + (r + 1)));
            } else if (!variantSet.add(v)) {
                errors.add(new ErrorLocation(batchId, r + 1, null, null, v,
                        "VARIANT_DUPLICATED", "duplicated variant: " + v));
            }
        }

        List<byte[]> genotypes = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<Integer> row = rows.get(r);
            byte[] encoded = new byte[samples.size()];
            for (int c = 0; c < samples.size(); c++) {
                Integer value = row.get(c);
                if (value == null || value == -1) {
                    encoded[c] = GenotypeEncoding.MISSING;
                } else if (value == 0 || value == 1 || value == 2) {
                    encoded[c] = value.byteValue();
                } else {
                    errors.add(new ErrorLocation(batchId, r + 1, c + FIXED_COLUMNS + 1,
                            samples.get(c), variants.get(r), "GENOTYPE_INVALID",
                            "genotype dosage must be 0, 1, 2 or -1 (missing), got " + value));
                    encoded[c] = GenotypeEncoding.MISSING;
                }
            }
            genotypes.add(encoded);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        ImportOptions options = request.options() == null ? ImportOptions.defaults() : request.options();
        return new CommittedBatch(batchId, List.copyOf(samples), List.copyOf(variants),
                List.copyOf(genotypes), options, System.currentTimeMillis());
    }

    @Override
    public CommittedBatch parseTsv(String batchId, Reader reader) throws IOException {
        if (batchId == null || batchId.isBlank()) {
            throw new ValidationException(List.of(ErrorLocation.of(null,
                    "BATCH_ID_MISSING", "batchId must not be blank")));
        }
        BufferedReader br = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        List<ErrorLocation> errors = new ArrayList<>();
        String header = null;
        int lineNo = 0;
        String line;
        while ((line = br.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("##")) {
                // meta-information line
                continue;
            }
            if (line.startsWith("#")) {
                String candidate = line.substring(1);
                String firstCol = candidate.split("\t", -1)[0].trim().toUpperCase();
                if (firstCol.startsWith("CHROM")) {
                    // conventional #CHROM header; drop exactly one leading '#'
                    header = candidate;
                } else {
                    // ordinary comment line
                    continue;
                }
            } else {
                header = line;
            }
            break;
        }
        if (header == null) {
            throw new ValidationException(List.of(ErrorLocation.of(batchId,
                    "FILE_EMPTY", "input contains no header row")));
        }
        String[] headerCols = header.split("\t", -1);
        if (headerCols.length < FIXED_COLUMNS + 1) {
            throw new ValidationException(List.of(new ErrorLocation(batchId, lineNo, null, null, null,
                    "HEADER_INVALID", "header must declare CHROM POS REF ALT plus at least one sample")));
        }
        List<String> samples = new ArrayList<>();
        for (int c = FIXED_COLUMNS; c < headerCols.length; c++) {
            samples.add(headerCols[c].trim());
        }

        List<Variant> variants = new ArrayList<>();
        List<byte[]> genotypes = new ArrayList<>();
        Set<String> sampleIds = new HashSet<>();
        for (int c = 0; c < samples.size(); c++) {
            if (samples.get(c).isBlank()) {
                errors.add(new ErrorLocation(batchId, lineNo, c + FIXED_COLUMNS + 1, null, null,
                        "SAMPLE_ID_MISSING", "blank sample id in header"));
            } else if (!sampleIds.add(samples.get(c))) {
                errors.add(new ErrorLocation(batchId, lineNo, c + FIXED_COLUMNS + 1, samples.get(c), null,
                        "SAMPLE_DUPLICATED", "duplicated sample in header: " + samples.get(c)));
            }
        }

        Set<Variant> variantSet = new HashSet<>();
        while ((line = br.readLine()) != null) {
            lineNo++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length != headerCols.length) {
                errors.add(new ErrorLocation(batchId, lineNo, null, null, null,
                        "ROW_SHAPE", "expected " + headerCols.length + " columns but got " + cols.length));
                continue;
            }
            Variant variant = null;
            try {
                long pos = Long.parseLong(cols[1].trim());
                variant = new Variant(cols[0].trim(), pos, cols[2].trim(), cols[3].trim());
            } catch (RuntimeException ex) {
                errors.add(new ErrorLocation(batchId, lineNo, null, null, null,
                        "VARIANT_INVALID", "invalid CHROM/POS/REF/ALT: " + ex.getMessage()));
            }
            if (variant != null && !variantSet.add(variant)) {
                errors.add(new ErrorLocation(batchId, lineNo, null, null, variant,
                        "VARIANT_DUPLICATED", "duplicated variant: " + variant));
            }
            byte[] row = new byte[samples.size()];
            for (int c = 0; c < samples.size(); c++) {
                try {
                    row[c] = GenotypeEncoding.parse(cols[c + FIXED_COLUMNS]);
                } catch (IllegalArgumentException ex) {
                    errors.add(new ErrorLocation(batchId, lineNo, c + FIXED_COLUMNS + 1,
                            c < samples.size() ? samples.get(c) : null, variant,
                            "GENOTYPE_INVALID", ex.getMessage()));
                    row[c] = GenotypeEncoding.MISSING;
                }
            }
            if (variant != null) {
                variants.add(variant);
                genotypes.add(row);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new CommittedBatch(batchId, List.copyOf(samples), List.copyOf(variants),
                List.copyOf(genotypes), ImportOptions.defaults(), System.currentTimeMillis());
    }

    private void checkShape(String batchId, List<String> samples, List<Variant> variants,
                            List<List<Integer>> rows, List<ErrorLocation> errors) {
        if (samples.isEmpty()) {
            errors.add(ErrorLocation.of(batchId, "SAMPLES_EMPTY", "batch contains no samples"));
        }
        if (variants.isEmpty()) {
            errors.add(ErrorLocation.of(batchId, "VARIANTS_EMPTY", "batch contains no variants"));
        }
        if (rows.size() != variants.size()) {
            errors.add(ErrorLocation.of(batchId, "ROW_SHAPE",
                    "genotype rows (" + rows.size() + ") must equal variants (" + variants.size() + ")"));
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Integer> row = rows.get(r);
            if (row == null || row.size() != samples.size()) {
                errors.add(new ErrorLocation(batchId, r + 1, null, null,
                        r < variants.size() ? variants.get(r) : null, "ROW_SHAPE",
                        "row " + (r + 1) + " must contain exactly " + samples.size() + " genotypes"));
            }
        }
    }
}
