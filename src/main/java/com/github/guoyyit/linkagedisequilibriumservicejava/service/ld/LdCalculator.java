package com.github.guoyyit.linkagedisequilibriumservicejava.service.ld;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskContext;

/** Pure LD computation over a single immutable snapshot. */
public interface LdCalculator {

    LdMatrix calculate(String taskId, DataSnapshot snapshot, long windowBp, TaskContext context);
}
