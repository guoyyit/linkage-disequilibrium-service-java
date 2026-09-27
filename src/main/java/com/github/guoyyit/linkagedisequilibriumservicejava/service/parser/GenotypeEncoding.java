package com.github.guoyyit.linkagedisequilibriumservicejava.service.parser;

import java.util.Locale;

/**
 * Encoding/decoding of genotype tokens to alternate allele dosage.
 *
 * <p>Accepted non-missing tokens (case-insensitive for allele letters):
 * <ul>
 *     <li>{@code 0},{@code 1},{@code 2} explicit dosage</li>
 *     <li>diploid allele pairs separated by {@code /} or {@code |}, e.g.
 *     {@code 0/0}, {@code 0|1}, {@code 1/1}; each allele must be 0 or 1</li>
 * </ul>
 *
 * <p>Missing tokens: empty string, {@code .}, {@code ./ .}-style pairs,
 * {@code NA}, {@code N}, {@code ?}. Any other token is invalid.
 */
public final class GenotypeEncoding {

    public static final byte MISSING = -1;

    private GenotypeEncoding() {
    }

    public static byte parse(String raw) {
        if (raw == null) {
            return MISSING;
        }
        String token = raw.trim();
        if (token.isEmpty() || ".".equals(token) || "NA".equalsIgnoreCase(token)
                || "N".equalsIgnoreCase(token) || "?".equals(token)) {
            return MISSING;
        }

        String explicit = token.toUpperCase(Locale.ROOT);
        if ("0".equals(explicit) || "1".equals(explicit) || "2".equals(explicit)) {
            return Byte.parseByte(explicit);
        }

        int sep = separatorIndex(token);
        if (sep >= 0) {
            String a = token.substring(0, sep).trim();
            String b = token.substring(sep + 1).trim();
            if (isMissingAllele(a) && isMissingAllele(b)) {
                return MISSING;
            }
            if (isMissingAllele(a) || isMissingAllele(b)) {
                // half-called genotypes carry no usable dosage
                return MISSING;
            }
            return (byte) (alleleValue(a, token) + alleleValue(b, token));
        }

        throw new IllegalArgumentException("invalid genotype token: '" + raw + "'");
    }

    private static int separatorIndex(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/' || c == '|') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMissingAllele(String allele) {
        return allele.isEmpty() || ".".equals(allele)
                || "N".equalsIgnoreCase(allele) || "?".equals(allele);
    }

    private static int alleleValue(String allele, String original) {
        if ("0".equals(allele)) {
            return 0;
        }
        if ("1".equals(allele)) {
            return 1;
        }
        throw new IllegalArgumentException("invalid genotype token: '" + original + "'");
    }
}
