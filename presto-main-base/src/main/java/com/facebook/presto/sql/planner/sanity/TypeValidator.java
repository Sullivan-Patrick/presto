/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.sanity;

import com.facebook.presto.Session;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeSignature;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.FunctionMetadata;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.AggregationNode.Aggregation;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.UnionNode;
import com.facebook.presto.spi.plan.WindowNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.SimplePlanVisitor;

import java.util.List;
import java.util.Map;

import static com.facebook.presto.common.type.UnknownType.UNKNOWN;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Ensures that all the expressions and FunctionCalls matches their output symbols
 */
public final class TypeValidator
        implements PlanChecker.Checker
{
    public TypeValidator() {}

    @Override
    public void validate(PlanNode plan, Session session, Metadata metadata, WarningCollector warningCollector)
    {
        plan.accept(new Visitor(metadata), null);
    }

    private static class Visitor
            extends SimplePlanVisitor<Void>
    {
        private final Metadata metadata;

        public Visitor(Metadata metadata)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
        }

        @Override
        public Void visitAggregation(AggregationNode node, Void context)
        {
            visitPlan(node, context);

            AggregationNode.Step step = node.getStep();

            switch (step) {
                case SINGLE:
                    checkFunctionSignature(node.getAggregations());
                    checkAggregation(node.getAggregations());
                    break;
                case PARTIAL:
                    verifyPartialAggregationTypes(node.getAggregations());
                    break;
                case INTERMEDIATE:
                    verifyIntermediateAggregationTypes(node.getAggregations());
                    break;
                case FINAL:
                    checkFunctionSignature(node.getAggregations());
                    break;
            }

            return null;
        }

        @Override
        public Void visitWindow(WindowNode node, Void context)
        {
            visitPlan(node, context);

            checkWindowFunctions(node.getWindowFunctions());

            return null;
        }

        @Override
        public Void visitProject(ProjectNode node, Void context)
        {
            visitPlan(node, context);

            for (Map.Entry<VariableReferenceExpression, RowExpression> entry : node.getAssignments().entrySet()) {
                RowExpression expression = entry.getValue();
                Type actualType = expression.getType();
                verifyTypeSignature(entry.getKey(), actualType.getTypeSignature());
            }

            return null;
        }

        @Override
        public Void visitUnion(UnionNode node, Void context)
        {
            visitPlan(node, context);

            for (VariableReferenceExpression keyVariable : node.getOutputVariables()) {
                List<VariableReferenceExpression> valueVariables = node.getVariableMapping().get(keyVariable);
                for (VariableReferenceExpression valueVariable : valueVariables) {
                    verifyTypeSignature(keyVariable, valueVariable.getType().getTypeSignature());
                }
            }

            return null;
        }

        private void checkWindowFunctions(Map<VariableReferenceExpression, WindowNode.Function> functions)
        {
            for (Map.Entry<VariableReferenceExpression, WindowNode.Function> entry : functions.entrySet()) {
                FunctionHandle functionHandle = entry.getValue().getFunctionHandle();
                CallExpression call = entry.getValue().getFunctionCall();

                verifyTypeSignature(entry.getKey(), metadata.getFunctionAndTypeManager().getFunctionMetadata(functionHandle).getReturnType());
                checkCall(entry.getKey(), call);
            }
        }

        private void checkCall(VariableReferenceExpression variable, CallExpression call)
        {
            Type actualType = call.getType();
            verifyTypeSignature(variable, actualType.getTypeSignature());
        }

        private void checkFunctionSignature(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                verifyTypeSignature(entry.getKey(), metadata.getFunctionAndTypeManager().getFunctionMetadata(entry.getValue().getFunctionHandle()).getReturnType());
            }
        }

        private void checkAggregation(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                VariableReferenceExpression variable = entry.getKey();
                Aggregation aggregation = entry.getValue();
                FunctionMetadata functionMetadata = metadata.getFunctionAndTypeManager().getFunctionMetadata(aggregation.getFunctionHandle());
                verifyTypeSignature(
                        variable,
                        functionMetadata.getReturnType());
                verifyTypeSignature(
                        variable,
                        aggregation.getCall().getType().getTypeSignature());
                int argumentSize = aggregation.getArguments().size();
                int expectedArgumentSize = functionMetadata.getArgumentTypes().size();
                checkArgument(argumentSize == expectedArgumentSize,
                        "Number of arguments is different from function signature: expected %s but got %s", expectedArgumentSize, argumentSize);
                List<TypeSignature> argumentTypes = aggregation.getArguments().stream()
                        .map(argument -> argument.getType().getTypeSignature())
                        .collect(toImmutableList());
                for (int i = 0; i < functionMetadata.getArgumentTypes().size(); i++) {
                    TypeSignature expected = functionMetadata.getArgumentTypes().get(i);
                    TypeSignature actual = argumentTypes.get(i);
                    FunctionAndTypeManager typeManager = metadata.getFunctionAndTypeManager();
                    if (!actual.equals(UNKNOWN.getTypeSignature()) && !typeManager.isTypeOnlyCoercion(typeManager.getType(actual), typeManager.getType(expected))) {
                        checkArgument(expected.equals(actual),
                                "Expected input types are %s but getting %s", functionMetadata.getArgumentTypes(), argumentTypes);
                    }
                }
            }
        }

        private void verifyTypeSignature(VariableReferenceExpression variable, TypeSignature actual)
        {
            // UNKNOWN should be considered as a wildcard type, which matches all the other types
            FunctionAndTypeManager functionAndTypeManager = metadata.getFunctionAndTypeManager();
            if (!actual.equals(UNKNOWN.getTypeSignature()) && !functionAndTypeManager.isTypeOnlyCoercion(functionAndTypeManager.getType(actual), variable.getType())) {
                checkArgument(variable.getType().getTypeSignature().equals(actual), "type of variable '%s' is expected to be %s, but the actual type is %s", variable.getName(), variable.getType(), actual);
            }
        }

        private void verifyPartialAggregationTypes(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                Aggregation aggregation = entry.getValue();

                TypeSignature actualTypeSig = aggregation.getCall().getType().getTypeSignature();
                verifyTypeSignature(entry.getKey(), actualTypeSig);
            }
        }

        private void verifyIntermediateAggregationTypes(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                Aggregation aggregation = entry.getValue();

                int argumentSize = aggregation.getArguments().size();
                checkArgument(argumentSize == 1,
                        "Number of arguments for intermediate aggregation is expected to be 1, got %s", argumentSize);

                TypeSignature expectedTypeSig = aggregation.getArguments().get(0).getType().getTypeSignature();
                TypeSignature actualTypeSig = aggregation.getCall().getType().getTypeSignature();
                checkArgument(
                        expectedTypeSig.equals(actualTypeSig),
                        "Return type for intermediate aggregation must be the same as the type of its single argument: expected '%s', got '%s'", expectedTypeSig, actualTypeSig);

                verifyTypeSignature(entry.getKey(), actualTypeSig);
            }
        }
    }
}
