package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * A biallelic variant locus identified by chromosome and 1-based position.
 */
public record Variant(String chromosome, long position, String referenceAllele, String alternateAllele)
        implements Comparable<Variant> {

    public Variant {
        if (chromosome == null || chromosome.isBlank()) {
            throw new IllegalArgumentException("chromosome must not be blank");
        }
        if (position <= 0) {
            throw new IllegalArgumentException("position must be positive: " + position);
        }
        referenceAllele = referenceAllele == null ? "" : referenceAllele;
        alternateAllele = alternateAllele == null ? "" : alternateAllele;
    }

    @Override
    public int compareTo(Variant o) {
        int c = chromosome.compareTo(o.chromosome);
        if (c != 0) {
            return c;
        }
        c = Long.compare(position, o.position);
        if (c != 0) {
            return c;
        }
        c = referenceAllele.compareTo(o.referenceAllele);
        if (c != 0) {
            return c;
        }
        return alternateAllele.compareTo(o.alternateAllele);
    }
}
