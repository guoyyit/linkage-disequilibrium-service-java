package com.github.guoyyit.linkagedisequilibriumservicejava.service.validator;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ErrorLocation;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Import-time quality control.
 *
 * <p>Variant rules: chromosome allow-list, inclusive position window, and
 * per-variant missing rate. Sample rule: per-sample missing rate. Every
 * rejection is collected with batch/row/column coordinates; nothing is
 * silently dropped. Cross-batch duplicate (variant, sample) values must not
 * conflict: missing never overwrites, distinct non-missing values are rejected.
 */
@Component
public class DefaultImportValidator implements ImportValidator {

    @Override
    public void validate(CommittedBatch candidate, SnapshotStore store) {
        List<ErrorLocation> errors = new ArrayList<>();
        ImportOptions o = candidate.options();
        int sampleCount = candidate.samples().size();
        int variantCount = candidate.variants().size();
        long[] sampleMissing = new long[sampleCount];

        for (int r = 0; r < variantCount; r++) {
            Variant v = candidate.variants().get(r);
            if (!o.allowedChromosomes().isEmpty()
                    && !o.allowedChromosomes().contains(v.chromosome())) {
                errors.add(new ErrorLocation(candidate.batchId(), r + 1, null, null, v,
                        "VARIANT_CHROMOSOME_REJECTED",
                        "chromosome " + v.chromosome() + " not allowed"));
            }
            if (o.minPosition() > 0 && v.position() < o.minPosition()) {
                errors.add(new ErrorLocation(candidate.batchId(), r + 1, null, null, v,
                        "VARIANT_POSITION_REJECTED",
                        "position " + v.position() + " < " + o.minPosition()));
            }
            if (o.maxPosition() > 0 && v.position() > o.maxPosition()) {
                errors.add(new ErrorLocation(candidate.batchId(), r + 1, null, null, v,
                        "VARIANT_POSITION_REJECTED",
                        "position " + v.position() + " > " + o.maxPosition()));
            }

            byte[] row = candidate.genotypes().get(r);
            long missing = 0;
            for (int c = 0; c < sampleCount; c++) {
                if (row[c] < 0) {
                    missing++;
                    sampleMissing[c]++;
                }
            }
            double rate = (double) missing / sampleCount;
            if (rate > o.variantMissingThreshold() + 1e-12) {
                errors.add(new ErrorLocation(candidate.batchId(), r + 1, null, null, v,
                        "VARIANT_MISSING_TOO_HIGH",
                        String.format("variant missing rate %.3f exceeds threshold %.3f",
                                rate, o.variantMissingThreshold())));
            }
        }

        for (int c = 0; c < sampleCount; c++) {
            double rate = (double) sampleMissing[c] / variantCount;
            if (rate > o.sampleMissingThreshold() + 1e-12) {
                errors.add(new ErrorLocation(candidate.batchId(), null, c + 5,
                        candidate.samples().get(c), null, "SAMPLE_MISSING_TOO_HIGH",
                        String.format("sample %s missing rate %.3f exceeds threshold %.3f",
                                candidate.samples().get(c), rate, o.sampleMissingThreshold())));
            }
        }

        checkCrossBatchConflicts(candidate, store.current(), errors);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private void checkCrossBatchConflicts(CommittedBatch candidate, DataSnapshot current,
                                          List<ErrorLocation> errors) {
        for (int r = 0; r < candidate.variants().size(); r++) {
            Variant v = candidate.variants().get(r);
            int vi = current.indexOfVariant(v);
            if (vi < 0) {
                continue;
            }
            byte[] candidateRow = candidate.genotypes().get(r);
            byte[] existingRow = current.dosageMatrix()[vi];
            for (int c = 0; c < candidate.samples().size(); c++) {
                byte incoming = candidateRow[c];
                if (incoming < 0) {
                    continue;
                }
                int si = current.indexOfSample(candidate.samples().get(c));
                if (si >= 0) {
                    byte existing = existingRow[si];
                    if (existing >= 0 && existing != incoming) {
                        errors.add(new ErrorLocation(candidate.batchId(), r + 1, c + 5,
                                candidate.samples().get(c), v, "GENOTYPE_CONFLICT",
                                "incoming dosage " + incoming + " conflicts with committed dosage "
                                        + existing));
                    }
                }
            }
        }
    }
}
