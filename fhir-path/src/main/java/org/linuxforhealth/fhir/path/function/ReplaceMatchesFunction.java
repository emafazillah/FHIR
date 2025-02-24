/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.linuxforhealth.fhir.path.function;

import static org.linuxforhealth.fhir.path.util.FHIRPathUtil.getStringValue;
import static org.linuxforhealth.fhir.path.util.FHIRPathUtil.singleton;

import java.util.Collection;
import java.util.List;

import org.linuxforhealth.fhir.path.FHIRPathNode;
import org.linuxforhealth.fhir.path.evaluator.FHIRPathEvaluator.EvaluationContext;

public class ReplaceMatchesFunction extends FHIRPathStringAbstractFunction {
    @Override
    public String getName() {
        return "replaceMatches";
    }

    @Override
    public int getMinArity() {
        return 2;
    }

    @Override
    public int getMaxArity() {
        return 2;
    }
    
    @Override
    public Collection<FHIRPathNode> doApply(EvaluationContext evaluationContext, Collection<FHIRPathNode> context, List<Collection<FHIRPathNode>> arguments) {
       
        return singleton(getStringValue(context).replaceMatches(getStringValue(arguments.get(0)), getStringValue(arguments.get(1))));
    }
}
