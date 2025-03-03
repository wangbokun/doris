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

package org.apache.doris.nereids.stats;

import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.common.Pair;
import org.apache.doris.nereids.memo.Group;
import org.apache.doris.nereids.memo.GroupExpression;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.Aggregate;
import org.apache.doris.nereids.trees.plans.algebra.EmptyRelation;
import org.apache.doris.nereids.trees.plans.algebra.Filter;
import org.apache.doris.nereids.trees.plans.algebra.Generate;
import org.apache.doris.nereids.trees.plans.algebra.Limit;
import org.apache.doris.nereids.trees.plans.algebra.OneRowRelation;
import org.apache.doris.nereids.trees.plans.algebra.Project;
import org.apache.doris.nereids.trees.plans.algebra.Repeat;
import org.apache.doris.nereids.trees.plans.algebra.Scan;
import org.apache.doris.nereids.trees.plans.algebra.SetOperation;
import org.apache.doris.nereids.trees.plans.algebra.TopN;
import org.apache.doris.nereids.trees.plans.algebra.Window;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.logical.LogicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalEsScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalExcept;
import org.apache.doris.nereids.trees.plans.logical.LogicalFileScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalGenerate;
import org.apache.doris.nereids.trees.plans.logical.LogicalIntersect;
import org.apache.doris.nereids.trees.plans.logical.LogicalJdbcScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalLimit;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalRepeat;
import org.apache.doris.nereids.trees.plans.logical.LogicalSchemaScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalSort;
import org.apache.doris.nereids.trees.plans.logical.LogicalTVFRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalUnion;
import org.apache.doris.nereids.trees.plans.logical.LogicalWindow;
import org.apache.doris.nereids.trees.plans.physical.PhysicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.physical.PhysicalDistribute;
import org.apache.doris.nereids.trees.plans.physical.PhysicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalEsScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalExcept;
import org.apache.doris.nereids.trees.plans.physical.PhysicalFileScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalFilter;
import org.apache.doris.nereids.trees.plans.physical.PhysicalGenerate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalHashAggregate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalHashJoin;
import org.apache.doris.nereids.trees.plans.physical.PhysicalIntersect;
import org.apache.doris.nereids.trees.plans.physical.PhysicalJdbcScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalLimit;
import org.apache.doris.nereids.trees.plans.physical.PhysicalNestedLoopJoin;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOlapScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalProject;
import org.apache.doris.nereids.trees.plans.physical.PhysicalQuickSort;
import org.apache.doris.nereids.trees.plans.physical.PhysicalRepeat;
import org.apache.doris.nereids.trees.plans.physical.PhysicalSchemaScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalStorageLayerAggregate;
import org.apache.doris.nereids.trees.plans.physical.PhysicalTVFRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalUnion;
import org.apache.doris.nereids.trees.plans.physical.PhysicalWindow;
import org.apache.doris.nereids.trees.plans.visitor.DefaultPlanVisitor;
import org.apache.doris.statistics.ColumnStatistic;
import org.apache.doris.statistics.ColumnStatisticBuilder;
import org.apache.doris.statistics.StatisticRange;
import org.apache.doris.statistics.Statistics;
import org.apache.doris.statistics.StatisticsBuilder;

import com.google.common.collect.Maps;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Used to calculate the stats for each plan
 */
public class StatsCalculator extends DefaultPlanVisitor<Statistics, Void> {
    private final GroupExpression groupExpression;

    private StatsCalculator(GroupExpression groupExpression) {
        this.groupExpression = groupExpression;
    }

    /**
     * estimate stats
     */
    public static void estimate(GroupExpression groupExpression) {
        StatsCalculator statsCalculator = new StatsCalculator(groupExpression);
        statsCalculator.estimate();
    }

    private void estimate() {
        Statistics stats = groupExpression.getPlan().accept(this, null);
        Statistics originStats = groupExpression.getOwnerGroup().getStatistics();
        /*
        in an ideal cost model, every group expression in a group are equivalent, but in fact the cost are different.
        we record the lowest expression cost as group cost to avoid missing this group.
        */
        if (originStats == null || originStats.getRowCount() > stats.getRowCount()) {
            groupExpression.getOwnerGroup().setStatistics(stats);
        }
        groupExpression.setEstOutputRowCount((long) stats.getRowCount());
        groupExpression.setStatDerived(true);
    }

    @Override
    public Statistics visitLogicalEmptyRelation(LogicalEmptyRelation emptyRelation, Void context) {
        return computeEmptyRelation(emptyRelation);
    }

    @Override
    public Statistics visitLogicalLimit(LogicalLimit<? extends Plan> limit, Void context) {
        return computeLimit(limit);
    }

    @Override
    public Statistics visitPhysicalLimit(PhysicalLimit<? extends Plan> limit, Void context) {
        return computeLimit(limit);
    }

    @Override
    public Statistics visitLogicalOneRowRelation(LogicalOneRowRelation oneRowRelation, Void context) {
        return computeOneRowRelation(oneRowRelation);
    }

    @Override
    public Statistics visitLogicalAggregate(LogicalAggregate<? extends Plan> aggregate, Void context) {
        return computeAggregate(aggregate);
    }

    @Override
    public Statistics visitLogicalRepeat(LogicalRepeat<? extends Plan> repeat, Void context) {
        return computeRepeat(repeat);
    }

    @Override
    public Statistics visitLogicalFilter(LogicalFilter<? extends Plan> filter, Void context) {
        return computeFilter(filter);
    }

    @Override
    public Statistics visitLogicalOlapScan(LogicalOlapScan olapScan, Void context) {
        olapScan.getExpressions();
        return computeScan(olapScan);
    }

    @Override
    public Statistics visitLogicalSchemaScan(LogicalSchemaScan schemaScan, Void context) {
        return computeScan(schemaScan);
    }

    @Override
    public Statistics visitLogicalFileScan(LogicalFileScan fileScan, Void context) {
        fileScan.getExpressions();
        return computeScan(fileScan);
    }

    @Override
    public Statistics visitLogicalTVFRelation(LogicalTVFRelation tvfRelation, Void context) {
        return tvfRelation.getFunction().computeStats(tvfRelation.getOutput());
    }

    @Override
    public Statistics visitLogicalJdbcScan(LogicalJdbcScan jdbcScan, Void context) {
        jdbcScan.getExpressions();
        return computeScan(jdbcScan);
    }

    @Override
    public Statistics visitLogicalEsScan(LogicalEsScan esScan, Void context) {
        esScan.getExpressions();
        return computeScan(esScan);
    }

    @Override
    public Statistics visitLogicalProject(LogicalProject<? extends Plan> project, Void context) {
        return computeProject(project);
    }

    @Override
    public Statistics visitLogicalSort(LogicalSort<? extends Plan> sort, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitLogicalTopN(LogicalTopN<? extends Plan> topN, Void context) {
        return computeTopN(topN);
    }

    @Override
    public Statistics visitLogicalJoin(LogicalJoin<? extends Plan, ? extends Plan> join, Void context) {
        return JoinEstimation.estimate(groupExpression.childStatistics(0),
                groupExpression.childStatistics(1), join);
    }

    @Override
    public Statistics visitLogicalAssertNumRows(
            LogicalAssertNumRows<? extends Plan> assertNumRows, Void context) {
        return computeAssertNumRows(assertNumRows.getAssertNumRowsElement().getDesiredNumOfRows());
    }

    @Override
    public Statistics visitLogicalUnion(
            LogicalUnion union, Void context) {
        return computeUnion(union);
    }

    @Override
    public Statistics visitLogicalExcept(
            LogicalExcept except, Void context) {
        return computeExcept(except);
    }

    @Override
    public Statistics visitLogicalIntersect(
            LogicalIntersect intersect, Void context) {
        return computeIntersect(intersect);
    }

    @Override
    public Statistics visitLogicalGenerate(LogicalGenerate<? extends Plan> generate, Void context) {
        return computeGenerate(generate);
    }

    public Statistics visitLogicalWindow(LogicalWindow<? extends Plan> window, Void context) {
        return computeWindow(window);
    }

    @Override
    public Statistics visitPhysicalWindow(PhysicalWindow window, Void context) {
        return computeWindow(window);
    }

    @Override
    public Statistics visitPhysicalEmptyRelation(PhysicalEmptyRelation emptyRelation, Void context) {
        return computeEmptyRelation(emptyRelation);
    }

    @Override
    public Statistics visitPhysicalHashAggregate(PhysicalHashAggregate<? extends Plan> agg, Void context) {
        return computeAggregate(agg);
    }

    @Override
    public Statistics visitPhysicalRepeat(PhysicalRepeat<? extends Plan> repeat, Void context) {
        return computeRepeat(repeat);
    }

    @Override
    public Statistics visitPhysicalOneRowRelation(PhysicalOneRowRelation oneRowRelation, Void context) {
        return computeOneRowRelation(oneRowRelation);
    }

    @Override
    public Statistics visitPhysicalOlapScan(PhysicalOlapScan olapScan, Void context) {
        return computeScan(olapScan);
    }

    @Override
    public Statistics visitPhysicalSchemaScan(PhysicalSchemaScan schemaScan, Void context) {
        return computeScan(schemaScan);
    }

    @Override
    public Statistics visitPhysicalFileScan(PhysicalFileScan fileScan, Void context) {
        return computeScan(fileScan);
    }

    @Override
    public Statistics visitPhysicalStorageLayerAggregate(
            PhysicalStorageLayerAggregate storageLayerAggregate, Void context) {
        return storageLayerAggregate.getRelation().accept(this, context);
    }

    @Override
    public Statistics visitPhysicalTVFRelation(PhysicalTVFRelation tvfRelation, Void context) {
        return tvfRelation.getFunction().computeStats(tvfRelation.getOutput());
    }

    @Override
    public Statistics visitPhysicalJdbcScan(PhysicalJdbcScan jdbcScan, Void context) {
        return computeScan(jdbcScan);
    }

    @Override
    public Statistics visitPhysicalEsScan(PhysicalEsScan esScan, Void context) {
        return computeScan(esScan);
    }

    @Override
    public Statistics visitPhysicalQuickSort(PhysicalQuickSort<? extends Plan> sort, Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitPhysicalTopN(PhysicalTopN<? extends Plan> topN, Void context) {
        return computeTopN(topN);
    }

    @Override
    public Statistics visitPhysicalHashJoin(
            PhysicalHashJoin<? extends Plan, ? extends Plan> hashJoin, Void context) {
        return JoinEstimation.estimate(groupExpression.childStatistics(0),
                groupExpression.childStatistics(1), hashJoin);
    }

    @Override
    public Statistics visitPhysicalNestedLoopJoin(
            PhysicalNestedLoopJoin<? extends Plan, ? extends Plan> nestedLoopJoin,
            Void context) {
        return JoinEstimation.estimate(groupExpression.childStatistics(0),
                groupExpression.childStatistics(1), nestedLoopJoin);
    }

    // TODO: We should subtract those pruned column, and consider the expression transformations in the node.
    @Override
    public Statistics visitPhysicalProject(PhysicalProject<? extends Plan> project, Void context) {
        return computeProject(project);
    }

    @Override
    public Statistics visitPhysicalFilter(PhysicalFilter<? extends Plan> filter, Void context) {
        return computeFilter(filter);
    }

    @Override
    public Statistics visitPhysicalDistribute(PhysicalDistribute<? extends Plan> distribute,
            Void context) {
        return groupExpression.childStatistics(0);
    }

    @Override
    public Statistics visitPhysicalAssertNumRows(PhysicalAssertNumRows<? extends Plan> assertNumRows,
            Void context) {
        return computeAssertNumRows(assertNumRows.getAssertNumRowsElement().getDesiredNumOfRows());
    }

    @Override
    public Statistics visitPhysicalUnion(PhysicalUnion union, Void context) {
        return computeUnion(union);
    }

    @Override
    public Statistics visitPhysicalExcept(PhysicalExcept except, Void context) {
        return computeExcept(except);
    }

    @Override
    public Statistics visitPhysicalIntersect(PhysicalIntersect intersect, Void context) {
        return computeIntersect(intersect);
    }

    @Override
    public Statistics visitPhysicalGenerate(PhysicalGenerate<? extends Plan> generate, Void context) {
        return computeGenerate(generate);
    }

    private Statistics computeAssertNumRows(long desiredNumOfRows) {
        Statistics statistics = groupExpression.childStatistics(0);
        statistics.withRowCount(Math.min(1, statistics.getRowCount()));
        return statistics;
    }

    private Statistics computeFilter(Filter filter) {
        FilterEstimation filterEstimation = new FilterEstimation();
        Statistics stats = groupExpression.childStatistics(0);
        return filterEstimation.estimate(filter.getPredicate(), stats);
    }

    // TODO: 1. Subtract the pruned partition
    //       2. Consider the influence of runtime filter
    //       3. Get NDV and column data size from StatisticManger, StatisticManager doesn't support it now.
    private Statistics computeScan(Scan scan) {
        Set<SlotReference> slotSet = scan.getOutput().stream().filter(SlotReference.class::isInstance)
                .map(s -> (SlotReference) s).collect(Collectors.toSet());
        Map<Expression, ColumnStatistic> columnStatisticMap = new HashMap<>();
        TableIf table = scan.getTable();
        double rowCount = scan.getTable().estimatedRowCount();
        for (SlotReference slotReference : slotSet) {
            String colName = slotReference.getName();
            if (colName == null) {
                throw new RuntimeException(String.format("Column %s not found", colName));
            }
            ColumnStatistic colStats =
                    Env.getCurrentEnv().getStatisticsCache().getColumnStatistics(table.getId(), colName);
            if (!colStats.isUnKnown) {
                rowCount = colStats.count;
            }
            columnStatisticMap.put(slotReference, colStats);
        }
        return new Statistics(rowCount, columnStatisticMap);
    }

    private Statistics computeTopN(TopN topN) {
        Statistics stats = groupExpression.childStatistics(0);
        return stats.withRowCount(Math.min(stats.getRowCount(), topN.getLimit()));
    }

    private Statistics computeLimit(Limit limit) {
        Statistics stats = groupExpression.childStatistics(0);
        return stats.withRowCount(Math.min(stats.getRowCount(), limit.getLimit()));
    }

    private Statistics computeAggregate(Aggregate<? extends Plan> aggregate) {
        // TODO: since we have no column stats here. just use a fix ratio to compute the row count.
        List<Expression> groupByExpressions = aggregate.getGroupByExpressions();
        Statistics childStats = groupExpression.childStatistics(0);
        Map<Expression, ColumnStatistic> childSlotToColumnStats = childStats.columnStatistics();
        double resultSetCount = groupByExpressions.stream().flatMap(expr -> expr.getInputSlots().stream())
                .filter(childSlotToColumnStats::containsKey).map(childSlotToColumnStats::get).map(s -> s.ndv)
                .reduce(1d, (a, b) -> a * b);
        if (resultSetCount <= 0) {
            resultSetCount = 1L;
        }
        resultSetCount = Math.min(resultSetCount, childStats.getRowCount());
        Map<Expression, ColumnStatistic> slotToColumnStats = Maps.newHashMap();
        List<NamedExpression> outputExpressions = aggregate.getOutputExpressions();
        // TODO: 1. Estimate the output unit size by the type of corresponding AggregateFunction
        //       2. Handle alias, literal in the output expression list
        for (NamedExpression outputExpression : outputExpressions) {
            ColumnStatistic columnStat = ExpressionEstimation.estimate(outputExpression, childStats);
            ColumnStatisticBuilder builder = new ColumnStatisticBuilder(columnStat);
            builder.setNdv(resultSetCount);
            slotToColumnStats.put(outputExpression.toSlot(), columnStat);
        }
        return new Statistics(resultSetCount, slotToColumnStats, childStats.getWidth(),
                childStats.getPenalty() + childStats.getRowCount());
        // TODO: Update ColumnStats properly, add new mapping from output slot to ColumnStats
    }

    private Statistics computeRepeat(Repeat<? extends Plan> repeat) {
        Statistics childStats = groupExpression.childStatistics(0);
        Map<Expression, ColumnStatistic> slotIdToColumnStats = childStats.columnStatistics();
        int groupingSetNum = repeat.getGroupingSets().size();
        double rowCount = childStats.getRowCount();
        Map<Expression, ColumnStatistic> columnStatisticMap = slotIdToColumnStats.entrySet()
                .stream().map(kv -> {
                    ColumnStatistic stats = kv.getValue();
                    ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder(stats);
                    columnStatisticBuilder
                            .setCount(stats.count < 0 ? stats.count : stats.count * groupingSetNum)
                            .setNumNulls(stats.numNulls < 0 ? stats.numNulls : stats.numNulls * groupingSetNum)
                            .setDataSize(stats.dataSize < 0 ? stats.dataSize : stats.dataSize * groupingSetNum);
                    return Pair.of(kv.getKey(), columnStatisticBuilder.build());
                }).collect(Collectors.toMap(Pair::key, Pair::value));
        return new Statistics(rowCount < 0 ? rowCount : rowCount * groupingSetNum, columnStatisticMap,
                childStats.getWidth(), childStats.getPenalty());
    }

    private Statistics computeProject(Project project) {
        List<NamedExpression> projections = project.getProjects();
        Statistics childStats = groupExpression.childStatistics(0);
        Map<Expression, ColumnStatistic> columnsStats = projections.stream().map(projection -> {
            ColumnStatistic columnStatistic = ExpressionEstimation.estimate(projection, childStats);
            return new SimpleEntry<>(projection.toSlot(), columnStatistic);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (item1, item2) -> item1));
        return new Statistics(childStats.getRowCount(), columnsStats, childStats.getWidth(), childStats.getPenalty());
    }

    private Statistics computeOneRowRelation(OneRowRelation oneRowRelation) {
        Map<Expression, ColumnStatistic> columnStatsMap = oneRowRelation.getProjects()
                .stream()
                .map(project -> {
                    ColumnStatistic statistic = new ColumnStatisticBuilder().setNdv(1).build();
                    // TODO: compute the literal size
                    return Pair.of(project.toSlot(), statistic);
                })
                .collect(Collectors.toMap(Pair::key, Pair::value));
        int rowCount = 1;
        return new Statistics(rowCount, columnStatsMap);
    }

    private Statistics computeEmptyRelation(EmptyRelation emptyRelation) {
        Map<Expression, ColumnStatistic> columnStatsMap = emptyRelation.getProjects()
                .stream()
                .map(project -> {
                    ColumnStatisticBuilder columnStat = new ColumnStatisticBuilder()
                            .setNdv(0)
                            .setNumNulls(0)
                            .setAvgSizeByte(0);
                    return Pair.of(project.toSlot(), columnStat.build());
                })
                .collect(Collectors.toMap(Pair::key, Pair::value));
        int rowCount = 0;
        return new Statistics(rowCount, columnStatsMap);
    }

    private Statistics computeUnion(SetOperation setOperation) {
        List<Slot> head = groupExpression.child(0).getLogicalProperties().getOutput();
        Statistics headStats = groupExpression.childStatistics(0);
        List<List<Slot>> childOutputs =
                groupExpression.children()
                        .stream().map(ge -> ge.getLogicalProperties().getOutput()).collect(Collectors.toList());
        List<Statistics> childStats =
                groupExpression.children().stream().map(Group::getStatistics).collect(Collectors.toList());
        StatisticsBuilder statisticsBuilder = new StatisticsBuilder();
        List<NamedExpression> unionOutput = setOperation.getOutputs();
        for (int i = 0; i < head.size(); i++) {
            double leftRowCount = headStats.getRowCount();
            Slot headSlot = head.get(i);
            for (int j = 1; j < childOutputs.size(); j++) {
                Slot slot = childOutputs.get(j).get(i);
                ColumnStatistic rightStatistic = childStats.get(j).findColumnStatistics(slot);
                double rightRowCount = childStats.get(j).getRowCount();
                ColumnStatistic estimatedColumnStatistics
                        = unionColumn(headStats.findColumnStatistics(headSlot),
                        headStats.getRowCount(), rightStatistic, rightRowCount);
                headStats.addColumnStats(headSlot, estimatedColumnStatistics);
                leftRowCount += childStats.get(j).getRowCount();
            }
            statisticsBuilder.setRowCount(leftRowCount);
            statisticsBuilder.putColumnStatistics(unionOutput.get(i), headStats.findColumnStatistics(headSlot));
        }
        return statisticsBuilder.build();
    }

    private Slot getLeftSlot(int fistSetOperation, int outputSlotIdx, SetOperation setOperation) {
        return fistSetOperation == 0
                ? setOperation.getFirstOutput().get(outputSlotIdx)
                : setOperation.getOutputs().get(outputSlotIdx).toSlot();
    }

    private ColumnStatistic getLeftStats(int fistSetOperation,
            Slot leftSlot,
            Map<Expression, ColumnStatistic> leftStatsSlotIdToColumnStats,
            Map<Expression, ColumnStatistic> newColumnStatsMap) {
        return fistSetOperation == 0
                ? leftStatsSlotIdToColumnStats.get(leftSlot.getExprId())
                : newColumnStatsMap.get(leftSlot.getExprId());
    }

    private Statistics computeExcept(SetOperation setOperation) {
        Statistics leftStats = groupExpression.childStatistics(0);
        List<NamedExpression> operatorOutput = setOperation.getOutputs();
        List<Slot> childSlots = groupExpression.child(0).getLogicalProperties().getOutput();
        StatisticsBuilder statisticsBuilder = new StatisticsBuilder();
        for (int i = 0; i < operatorOutput.size(); i++) {
            ColumnStatistic columnStatistic = leftStats.findColumnStatistics(childSlots.get(i));
            statisticsBuilder.putColumnStatistics(operatorOutput.get(i), columnStatistic);
        }
        statisticsBuilder.setRowCount(leftStats.getRowCount());
        return statisticsBuilder.build();
    }

    private Statistics computeIntersect(SetOperation setOperation) {
        Statistics leftChildStats = groupExpression.childStatistics(0);
        double rowCount = leftChildStats.getRowCount();
        for (int i = 1; i < setOperation.getArity(); ++i) {
            rowCount = Math.min(rowCount, groupExpression.childStatistics(i).getRowCount());
        }
        double minProd = Double.MAX_VALUE;
        for (Group group : groupExpression.children()) {
            Statistics statistics = group.getStatistics();
            double prod = 1.0;
            for (ColumnStatistic columnStatistic : statistics.columnStatistics().values()) {
                prod *= columnStatistic.ndv;
            }
            if (minProd < prod) {
                minProd = prod;
            }
        }
        rowCount = Math.min(rowCount, minProd);
        List<NamedExpression> outputs = setOperation.getOutputs();
        List<Slot> leftChildOutputs = setOperation.getChildOutput(0);
        for (int i = 0; i < outputs.size(); i++) {
            leftChildStats.addColumnStats(outputs.get(i),
                    leftChildStats.findColumnStatistics(leftChildOutputs.get(i)));
        }
        return leftChildStats.withRowCount(rowCount);
    }

    private Statistics computeGenerate(Generate generate) {
        Statistics stats = groupExpression.childStatistics(0);
        double count = stats.getRowCount() * generate.getGeneratorOutput().size() * 5;
        Map<Expression, ColumnStatistic> columnStatsMap = Maps.newHashMap();
        for (Map.Entry<Expression, ColumnStatistic> entry : stats.columnStatistics().entrySet()) {
            ColumnStatistic columnStatistic = new ColumnStatisticBuilder(entry.getValue()).setCount(count).build();
            columnStatsMap.put(entry.getKey(), columnStatistic);
        }
        for (Slot output : generate.getGeneratorOutput()) {
            ColumnStatistic columnStatistic = new ColumnStatisticBuilder()
                    .setCount(count)
                    .setMinValue(Double.MAX_VALUE)
                    .setMaxValue(Double.MIN_VALUE)
                    .setNdv(count)
                    .setNumNulls(0)
                    .setAvgSizeByte(output.getDataType().width())
                    .build();
            columnStatsMap.put(output, columnStatistic);
        }
        return new Statistics(count, columnStatsMap);
    }

    private Statistics computeWindow(Window windowOperator) {
        Statistics stats = groupExpression.childStatistics(0);
        Map<Expression, ColumnStatistic> childColumnStats = stats.columnStatistics();
        Map<Expression, ColumnStatistic> columnStatisticMap = windowOperator.getWindowExpressions().stream()
                .map(expr -> {
                    ColumnStatistic value = null;
                    Set<Slot> slots = expr.getInputSlots();
                    if (slots.isEmpty()) {
                        value = ColumnStatistic.UNKNOWN;
                    } else {
                        for (Slot slot : slots) {
                            if (childColumnStats.containsKey(slot)) {
                                value = childColumnStats.get(slot);
                                break;
                            }
                        }
                        if (value == null) {
                            // todo: how to set stats?
                            value = ColumnStatistic.UNKNOWN;
                        }
                    }
                    return Pair.of(expr.toSlot(), value);
                }).collect(Collectors.toMap(Pair::key, Pair::value));
        columnStatisticMap.putAll(childColumnStats);
        return new Statistics(stats.getRowCount(), columnStatisticMap);
    }

    private ColumnStatistic unionColumn(ColumnStatistic leftStats, double leftRowCount, ColumnStatistic rightStats,
            double rightRowCount) {
        ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder();
        columnStatisticBuilder.setMaxValue(Math.max(leftStats.maxValue, rightStats.maxValue));
        columnStatisticBuilder.setMinValue(Math.min(leftStats.minValue, rightStats.minValue));
        StatisticRange leftRange = StatisticRange.from(leftStats);
        StatisticRange rightRange = StatisticRange.from(rightStats);
        StatisticRange newRange = leftRange.union(rightRange);
        double newRowCount = leftRowCount + rightRowCount;
        double leftSize = (leftRowCount - leftStats.numNulls) * leftStats.avgSizeByte;
        double rightSize = (rightRowCount - rightStats.numNulls) * rightStats.avgSizeByte;
        double newNullFraction = (leftStats.numNulls + rightStats.numNulls) / StatsMathUtil.maxNonNaN(1, newRowCount);
        double newNonNullRowCount = newRowCount * (1 - newNullFraction);

        double newAverageRowSize = newNonNullRowCount == 0 ? 0 : (leftSize + rightSize) / newNonNullRowCount;
        columnStatisticBuilder.setMinValue(newRange.getLow())
                .setMaxValue(newRange.getHigh())
                .setNdv(newRange.getDistinctValues())
                .setNumNulls(leftStats.numNulls + rightStats.numNulls)
                .setAvgSizeByte(newAverageRowSize);
        return columnStatisticBuilder.build();
    }
}
