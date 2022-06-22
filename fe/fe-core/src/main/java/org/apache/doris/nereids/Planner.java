// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids;

import org.apache.doris.common.AnalysisException;
import org.apache.doris.nereids.jobs.cascades.OptimizeGroupJob;
import org.apache.doris.nereids.jobs.rewrite.RewriteBottomUpJob;
import org.apache.doris.nereids.memo.Group;
import org.apache.doris.nereids.memo.GroupExpression;
import org.apache.doris.nereids.memo.Memo;
import org.apache.doris.nereids.properties.PhysicalProperties;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalPlan;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * Planner to do query plan in Nereids.
 */
public class Planner {
    private PlannerContext plannerContext;

    /**
     * Do analyze and optimize for query plan.
     *
     * @param plan wait for plan
     * @param outputProperties physical properties constraints
     * @param connectContext connect context for this query
     * @return physical plan generated by this planner
     * @throws AnalysisException throw exception if failed in ant stage
     */
    // TODO: refactor, just demo code here
    public PhysicalPlan plan(LogicalPlan plan, PhysicalProperties outputProperties, ConnectContext connectContext)
            throws AnalysisException {
        Memo memo = new Memo();
        memo.initialize(plan);

        OptimizerContext optimizerContext = new OptimizerContext(memo);
        plannerContext = new PlannerContext(optimizerContext, connectContext, outputProperties);

        plannerContext.getOptimizerContext().pushJob(
                new RewriteBottomUpJob(getRoot(), optimizerContext.getRuleSet().getAnalysisRules(), plannerContext));

        plannerContext.getOptimizerContext().pushJob(new OptimizeGroupJob(getRoot(), plannerContext));
        plannerContext.getOptimizerContext().getJobScheduler().executeJobPool(plannerContext);

        // Get plan directly. Just for SSB.
        return getRoot().extractPlan();
    }

    public Group getRoot() {
        return plannerContext.getOptimizerContext().getMemo().getRoot();
    }

    private PhysicalPlan chooseBestPlan(Group rootGroup, PhysicalProperties physicalProperties)
            throws AnalysisException {
        GroupExpression groupExpression = rootGroup.getLowestCostPlan(physicalProperties).orElseThrow(
                () -> new AnalysisException("lowestCostPlans with physicalProperties doesn't exist")).second;
        List<PhysicalProperties> inputPropertiesList = groupExpression.getInputPropertiesList(physicalProperties);

        List<Plan> planChildren = Lists.newArrayList();
        for (int i = 0; i < groupExpression.arity(); i++) {
            planChildren.add(chooseBestPlan(groupExpression.child(i), inputPropertiesList.get(i)));
        }

        Plan plan = ((PhysicalPlan) groupExpression.getOperator().toTreeNode(groupExpression)).withChildren(
                planChildren);
        if (!(plan instanceof PhysicalPlan)) {
            throw new AnalysisException("generate logical plan");
        }
        PhysicalPlan physicalPlan = (PhysicalPlan) plan;

        // TODO: set (logical and physical)properties/statistics/... for physicalPlan.

        return physicalPlan;
    }
}
