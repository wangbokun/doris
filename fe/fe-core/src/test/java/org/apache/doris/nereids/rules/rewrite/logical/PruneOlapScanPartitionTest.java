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

package org.apache.doris.nereids.rules.rewrite.logical;

import org.apache.doris.analysis.IntLiteral;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.PartitionItem;
import org.apache.doris.catalog.PartitionKey;
import org.apache.doris.catalog.RangePartitionInfo;
import org.apache.doris.catalog.RangePartitionItem;
import org.apache.doris.catalog.Type;
import org.apache.doris.common.jmockit.Deencapsulation;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.GreaterThan;
import org.apache.doris.nereids.trees.expressions.GreaterThanEqual;
import org.apache.doris.nereids.trees.expressions.LessThan;
import org.apache.doris.nereids.trees.expressions.LessThanEqual;
import org.apache.doris.nereids.trees.expressions.Or;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.literal.IntegerLiteral;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.nereids.util.MemoPatternMatchSupported;
import org.apache.doris.nereids.util.MemoTestUtils;
import org.apache.doris.nereids.util.PlanChecker;
import org.apache.doris.nereids.util.PlanConstructor;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class PruneOlapScanPartitionTest implements MemoPatternMatchSupported {

    @Test
    void testOlapScanPartitionWithSingleColumnCase(@Mocked OlapTable olapTable) throws Exception {
        List<Column> columnNameList = new ArrayList<>();
        columnNameList.add(new Column("col1", Type.INT.getPrimitiveType()));
        columnNameList.add(new Column("col2", Type.INT.getPrimitiveType()));
        Map<Long, PartitionItem> keyItemMap = new HashMap<>();
        PartitionKey k0 = new PartitionKey();
        k0.pushColumn(new IntLiteral(0), Type.INT.getPrimitiveType());
        PartitionKey k1 = new PartitionKey();
        k1.pushColumn(new IntLiteral(5), Type.INT.getPrimitiveType());
        keyItemMap.put(0L, new RangePartitionItem(Range.range(k0, BoundType.CLOSED, k1, BoundType.OPEN)));
        PartitionKey k2 = new PartitionKey();
        k2.pushColumn(new IntLiteral(5), Type.INT.getPrimitiveType());
        PartitionKey k3 = new PartitionKey();
        k3.pushColumn(new IntLiteral(10), Type.INT.getPrimitiveType());
        keyItemMap.put(1L, new RangePartitionItem(Range.range(k2, BoundType.CLOSED, k3, BoundType.OPEN)));
        RangePartitionInfo rangePartitionInfo = new RangePartitionInfo(columnNameList);
        Deencapsulation.setField(rangePartitionInfo, "idToItem", keyItemMap);
        new Expectations() {{
                olapTable.getPartitionInfo();
                result = rangePartitionInfo;
                olapTable.getPartitionColumnNames();
                result = rangePartitionInfo.getPartitionColumns().stream().map(c -> c.getName().toLowerCase())
                        .collect(Collectors.toSet());
                olapTable.getName();
                result = "tbl";
            }};
        LogicalOlapScan scan = new LogicalOlapScan(PlanConstructor.getNextRelationId(), olapTable);
        SlotReference slotRef = new SlotReference("col1", IntegerType.INSTANCE);
        Expression expression = new LessThan(slotRef, new IntegerLiteral(4));
        LogicalFilter<LogicalOlapScan> filter = new LogicalFilter<>(ImmutableSet.of(expression), scan);

        PlanChecker.from(MemoTestUtils.createConnectContext(), filter)
                .applyTopDown(new PruneOlapScanPartition())
                .matches(
                        logicalFilter(
                                logicalOlapScan().when(
                                        olapScan -> olapScan.getSelectedPartitionIds().iterator().next() == 0L)
                        )
                );

        Expression lessThan0 = new LessThan(slotRef, new IntegerLiteral(0));
        Expression greaterThan6 = new GreaterThan(slotRef, new IntegerLiteral(6));
        Or lessThan0OrGreaterThan6 = new Or(lessThan0, greaterThan6);
        filter = new LogicalFilter<>(ImmutableSet.of(lessThan0OrGreaterThan6), scan);
        scan = new LogicalOlapScan(PlanConstructor.getNextRelationId(), olapTable);

        PlanChecker.from(MemoTestUtils.createConnectContext(), filter)
                .applyTopDown(new PruneOlapScanPartition())
                .matches(
                        logicalFilter(
                                logicalOlapScan().when(
                                        olapScan -> olapScan.getSelectedPartitionIds().iterator().next() == 1L)
                        )
                );

        Expression greaterThanEqual0 =
                new GreaterThanEqual(
                        slotRef, new IntegerLiteral(0));
        Expression lessThanEqual5 =
                new LessThanEqual(slotRef, new IntegerLiteral(5));
        scan = new LogicalOlapScan(PlanConstructor.getNextRelationId(), olapTable);
        filter = new LogicalFilter<>(ImmutableSet.of(greaterThanEqual0, lessThanEqual5), scan);

        PlanChecker.from(MemoTestUtils.createConnectContext(), filter)
                .applyTopDown(new PruneOlapScanPartition())
                .matches(
                        logicalFilter(
                                logicalOlapScan().when(
                                                olapScan -> olapScan.getSelectedPartitionIds().iterator().next() == 0L)
                                        .when(olapScan -> olapScan.getSelectedPartitionIds().size() == 2)
                        )
                );
    }

    @Test
    void testOlapScanPartitionPruneWithMultiColumnCase(@Mocked OlapTable olapTable) throws Exception {
        List<Column> columnNameList = new ArrayList<>();
        columnNameList.add(new Column("col1", Type.INT.getPrimitiveType()));
        columnNameList.add(new Column("col2", Type.INT.getPrimitiveType()));
        Map<Long, PartitionItem> keyItemMap = new HashMap<>();
        PartitionKey k0 = new PartitionKey();
        k0.pushColumn(new IntLiteral(1), Type.INT.getPrimitiveType());
        k0.pushColumn(new IntLiteral(10), Type.INT.getPrimitiveType());
        PartitionKey k1 = new PartitionKey();
        k1.pushColumn(new IntLiteral(4), Type.INT.getPrimitiveType());
        k1.pushColumn(new IntLiteral(5), Type.INT.getPrimitiveType());
        keyItemMap.put(0L, new RangePartitionItem(Range.range(k0, BoundType.CLOSED, k1, BoundType.OPEN)));
        RangePartitionInfo rangePartitionInfo = new RangePartitionInfo(columnNameList);
        Deencapsulation.setField(rangePartitionInfo, "idToItem", keyItemMap);
        new Expectations() {{
                olapTable.getPartitionInfo();
                result = rangePartitionInfo;
                olapTable.getPartitionColumnNames();
                result = rangePartitionInfo.getPartitionColumns().stream().map(c -> c.getName().toLowerCase())
                        .collect(Collectors.toSet());
                olapTable.getName();
                result = "tbl";
            }};
        LogicalOlapScan scan = new LogicalOlapScan(PlanConstructor.getNextRelationId(), olapTable);
        Expression left = new LessThan(new SlotReference("col1", IntegerType.INSTANCE), new IntegerLiteral(4));
        Expression right = new GreaterThan(new SlotReference("col2", IntegerType.INSTANCE), new IntegerLiteral(11));
        LogicalFilter<LogicalOlapScan> filter = new LogicalFilter<>(ImmutableSet.of(left, right), scan);
        PlanChecker.from(MemoTestUtils.createConnectContext(), filter)
                .applyTopDown(new PruneOlapScanPartition())
                .matches(
                        logicalFilter(
                                logicalOlapScan()
                                        .when(
                                                olapScan -> olapScan.getSelectedPartitionIds().iterator().next() == 0L)
                        )
                );
    }
}
