package com.github.guoyyit.linkagedisequilibriumservicejava.service.filter;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.FilterResponse;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdFilterCriteria;

/** Deterministic, reproducible variant selection over a single snapshot. */
public interface VariantFilter {

    FilterResponse select(LdFilterCriteria criteria);
}
