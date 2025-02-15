/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.rules.logical;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.TableDescriptor;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceFunctionProvider;
import org.apache.flink.table.connector.source.abilities.SupportsReadingMetadata;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.planner.calcite.CalciteConfig;
import org.apache.flink.table.planner.plan.optimize.program.BatchOptimizeContext;
import org.apache.flink.table.planner.plan.optimize.program.FlinkBatchProgram;
import org.apache.flink.table.planner.plan.optimize.program.FlinkHepRuleSetProgramBuilder;
import org.apache.flink.table.planner.plan.optimize.program.HEP_RULES_EXECUTION_TYPE;
import org.apache.flink.table.planner.utils.TableConfigUtils;
import org.apache.flink.table.types.DataType;

import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.tools.RuleSets;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.table.api.DataTypes.STRING;
import static org.apache.flink.table.planner.plan.rules.logical.PushProjectIntoTableSourceScanRuleTest.MetadataNoProjectionPushDownTableFactory.SUPPORTS_METADATA_PROJECTION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

/** Test for {@link PushProjectIntoTableSourceScanRule}. */
public class PushProjectIntoTableSourceScanRuleTest
        extends PushProjectIntoLegacyTableSourceScanRuleTest {

    @Override
    public void setup() {
        util().buildBatchProgram(FlinkBatchProgram.DEFAULT_REWRITE());
        CalciteConfig calciteConfig =
                TableConfigUtils.getCalciteConfig(util().tableEnv().getConfig());
        calciteConfig
                .getBatchProgram()
                .get()
                .addLast(
                        "rules",
                        FlinkHepRuleSetProgramBuilder.<BatchOptimizeContext>newBuilder()
                                .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_SEQUENCE())
                                .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
                                .add(RuleSets.ofList(PushProjectIntoTableSourceScanRule.INSTANCE))
                                .build());

        String ddl1 =
                "CREATE TABLE MyTable (\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c string\n"
                        + ") WITH (\n"
                        + " 'connector' = 'values',\n"
                        + " 'bounded' = 'true'\n"
                        + ")";
        util().tableEnv().executeSql(ddl1);

        String ddl2 =
                "CREATE TABLE VirtualTable (\n"
                        + "  a int,\n"
                        + "  b bigint,\n"
                        + "  c string,\n"
                        + "  d as a + 1\n"
                        + ") WITH (\n"
                        + " 'connector' = 'values',\n"
                        + " 'bounded' = 'true'\n"
                        + ")";
        util().tableEnv().executeSql(ddl2);

        String ddl3 =
                "CREATE TABLE NestedTable (\n"
                        + "  id int,\n"
                        + "  deepNested row<nested1 row<name string, `value` int>, nested2 row<num int, flag boolean>>,\n"
                        + "  nested row<name string, `value` int>,\n"
                        + "  `deepNestedWith.` row<`.value` int, nested row<name string, `.value` int>>,\n"
                        + "  name string,\n"
                        + "  testMap Map<string, string>\n"
                        + ") WITH (\n"
                        + " 'connector' = 'values',\n"
                        + " 'nested-projection-supported' = 'true',"
                        + " 'bounded' = 'true'\n"
                        + ")";
        util().tableEnv().executeSql(ddl3);

        String ddl4 =
                "CREATE TABLE MetadataTable(\n"
                        + "  id int,\n"
                        + "  deepNested row<nested1 row<name string, `value` int>, nested2 row<num int, flag boolean>>,\n"
                        + "  metadata_1 int metadata,\n"
                        + "  metadata_2 string metadata\n"
                        + ") WITH ("
                        + " 'connector' = 'values',"
                        + " 'nested-projection-supported' = 'true',"
                        + " 'bounded' = 'true',\n"
                        + " 'readable-metadata' = 'metadata_1:INT, metadata_2:STRING, metadata_3:BIGINT'"
                        + ")";
        util().tableEnv().executeSql(ddl4);

        String ddl5 =
                "CREATE TABLE UpsertTable("
                        + "  id int,\n"
                        + "  deepNested row<nested1 row<name string, `value` int>, nested2 row<num int, flag boolean>>,\n"
                        + "  metadata_1 int metadata,\n"
                        + "  metadata_2 string metadata,\n"
                        + "  PRIMARY KEY(id, deepNested) NOT ENFORCED"
                        + ") WITH ("
                        + "  'connector' = 'values',"
                        + "  'nested-projection-supported' = 'true',"
                        + "  'bounded' = 'false',\n"
                        + "  'changelod-mode' = 'I,UB,D',"
                        + " 'readable-metadata' = 'metadata_1:INT, metadata_2:STRING, metadata_3:BIGINT'"
                        + ")";
        util().tableEnv().executeSql(ddl5);

        String ddl6 =
                "CREATE TABLE NestedItemTable (\n"
                        + "  `ID` INT,\n"
                        + "  `Timestamp` TIMESTAMP(3),\n"
                        + "  `Result` ROW<\n"
                        + "    `Mid` ROW<"
                        + "      `data_arr` ROW<`value` BIGINT> ARRAY,\n"
                        + "      `data_map` MAP<STRING, ROW<`value` BIGINT>>"
                        + "     >"
                        + "   >,\n"
                        + "   WATERMARK FOR `Timestamp` AS `Timestamp`\n"
                        + ") WITH (\n"
                        + " 'connector' = 'values',\n"
                        + " 'nested-projection-supported' = 'true',"
                        + " 'bounded' = 'true'\n"
                        + ")";
        util().tableEnv().executeSql(ddl6);

        String ddl7 =
                "CREATE TABLE ItemTable (\n"
                        + "  `ID` INT,\n"
                        + "  `Timestamp` TIMESTAMP(3),\n"
                        + "  `Result` ROW<\n"
                        + "    `data_arr` ROW<`value` BIGINT> ARRAY,\n"
                        + "    `data_map` MAP<STRING, ROW<`value` BIGINT>>>,\n"
                        + "  `outer_array` ARRAY<INT>,\n"
                        + "  `outer_map` MAP<STRING, STRING>,\n"
                        + "   WATERMARK FOR `Timestamp` AS `Timestamp`\n"
                        + ") WITH (\n"
                        + " 'connector' = 'values',\n"
                        + " 'bounded' = 'true'\n"
                        + ")";
        util().tableEnv().executeSql(ddl7);
    }

    @Test
    public void testProjectWithMapType() {
        String sqlQuery = "SELECT id, testMap['e']\n" + "FROM NestedTable";
        util().verifyRelPlan(sqlQuery);
    }

    @Override
    @Test
    public void testNestedProject() {
        String sqlQuery =
                "SELECT id,\n"
                        + "    deepNested.nested1.name AS nestedName,\n"
                        + "    nested.`value` AS nestedValue,\n"
                        + "    deepNested.nested2.flag AS nestedFlag,\n"
                        + "    deepNested.nested2.num AS nestedNum\n"
                        + "FROM NestedTable";
        util().verifyRelPlan(sqlQuery);
    }

    @Test
    public void testComplicatedNestedProject() {
        String sqlQuery =
                "SELECT id,"
                        + "    deepNested.nested1.name AS nestedName,\n"
                        + "    (`deepNestedWith.`.`.value` + `deepNestedWith.`.nested.`.value`) AS nestedSum\n"
                        + "FROM NestedTable";
        util().verifyRelPlan(sqlQuery);
    }

    @Test
    public void testNestProjectWithMetadata() {
        String sqlQuery =
                "SELECT id,"
                        + "    deepNested.nested1 AS nested1,\n"
                        + "    deepNested.nested1.`value` + deepNested.nested2.num + metadata_1 as results\n"
                        + "FROM MetadataTable";

        util().verifyRelPlan(sqlQuery);
    }

    @Test
    public void testNestProjectWithUpsertSource() {
        String sqlQuery =
                "SELECT id,"
                        + "    deepNested.nested1 AS nested1,\n"
                        + "    deepNested.nested1.`value` + deepNested.nested2.num + metadata_1 as results\n"
                        + "FROM MetadataTable";

        util().verifyRelPlan(sqlQuery);
    }

    @Test
    public void testNestedProjectFieldAccessWithITEM() {
        util().verifyRelPlan(
                        "SELECT "
                                + "`Result`.`Mid`.data_arr[ID].`value`, "
                                + "`Result`.`Mid`.data_map['item'].`value` "
                                + "FROM NestedItemTable");
    }

    @Test
    public void testNestedProjectFieldAccessWithITEMWithConstantIndex() {
        util().verifyRelPlan(
                        "SELECT "
                                + "`Result`.`Mid`.data_arr[2].`value`, "
                                + "`Result`.`Mid`.data_arr "
                                + "FROM NestedItemTable");
    }

    @Test
    public void testNestedProjectFieldAccessWithITEMContainsTopLevelAccess() {
        util().verifyRelPlan(
                        "SELECT "
                                + "`Result`.`Mid`.data_arr[2].`value`, "
                                + "`Result`.`Mid`.data_arr[ID].`value`, "
                                + "`Result`.`Mid`.data_map['item'].`value`, "
                                + "`Result`.`Mid` "
                                + "FROM NestedItemTable");
    }

    @Test
    public void testProjectFieldAccessWithITEM() {
        util().verifyRelPlan(
                        "SELECT "
                                + "`Result`.data_arr[ID].`value`, "
                                + "`Result`.data_map['item'].`value`, "
                                + "`outer_array`[1], "
                                + "`outer_array`[ID], "
                                + "`outer_map`['item'] "
                                + "FROM ItemTable");
    }

    @Test
    public void testMetadataProjectionWithoutProjectionPushDownWhenSupported() {
        createMetadataTableWithoutProjectionPushDown("T1", true);

        util().verifyRelPlan("SELECT m1, metadata FROM T1");
        assertThat(
                MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.get(),
                contains("m1", "m2"));
    }

    @Test
    public void testMetadataProjectionWithoutProjectionPushDownWhenNotSupported() {
        createMetadataTableWithoutProjectionPushDown("T2", false);

        util().verifyRelPlan("SELECT m1, metadata FROM T2");
        assertThat(
                MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.get(),
                contains("m1", "m2", "m3"));
    }

    @Test
    public void testMetadataProjectionWithoutProjectionPushDownWhenSupportedAndNoneSelected() {
        createMetadataTableWithoutProjectionPushDown("T3", true);

        util().verifyRelPlan("SELECT 1 FROM T3");
        assertThat(MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.get(), hasSize(0));
    }

    @Test
    public void testMetadataProjectionWithoutProjectionPushDownWhenNotSupportedAndNoneSelected() {
        createMetadataTableWithoutProjectionPushDown("T4", false);

        util().verifyRelPlan("SELECT 1 FROM T4");
        assertThat(
                MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.get(),
                contains("m1", "m2", "m3"));
    }

    // ---------------------------------------------------------------------------------------------

    private void createMetadataTableWithoutProjectionPushDown(
            String name, boolean supportsMetadataProjection) {
        util().tableEnv()
                .createTable(
                        name,
                        TableDescriptor.forConnector(
                                        MetadataNoProjectionPushDownTableFactory.IDENTIFIER)
                                .schema(
                                        Schema.newBuilder()
                                                .columnByMetadata("m1", STRING())
                                                .columnByMetadata("metadata", STRING(), "m2")
                                                .columnByMetadata("m3", STRING())
                                                .build())
                                .option(SUPPORTS_METADATA_PROJECTION, supportsMetadataProjection)
                                .build());
    }

    // ---------------------------------------------------------------------------------------------

    /** Factory for {@link Source}. */
    public static class MetadataNoProjectionPushDownTableFactory
            implements DynamicTableSourceFactory {
        public static final String IDENTIFIER = "metadataNoProjectionPushDown";

        public static final ConfigOption<Boolean> SUPPORTS_METADATA_PROJECTION =
                ConfigOptions.key("supports-metadata-projection").booleanType().defaultValue(true);

        public static ThreadLocal<List<String>> appliedMetadataKeys = new ThreadLocal<>();

        @Override
        public String factoryIdentifier() {
            return IDENTIFIER;
        }

        @Override
        public Set<ConfigOption<?>> requiredOptions() {
            return Collections.emptySet();
        }

        @Override
        public Set<ConfigOption<?>> optionalOptions() {
            return Collections.singleton(SUPPORTS_METADATA_PROJECTION);
        }

        @Override
        public DynamicTableSource createDynamicTableSource(Context context) {
            FactoryUtil.TableFactoryHelper helper =
                    FactoryUtil.createTableFactoryHelper(this, context);
            return new Source(helper.getOptions());
        }
    }

    private static class Source implements ScanTableSource, SupportsReadingMetadata {

        private final ReadableConfig options;

        public Source(ReadableConfig options) {
            this.options = options;
            MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.remove();
        }

        @Override
        public ChangelogMode getChangelogMode() {
            return ChangelogMode.insertOnly();
        }

        @Override
        public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtimeProviderContext) {
            return SourceFunctionProvider.of(
                    new SourceFunction<RowData>() {
                        @Override
                        public void run(SourceContext<RowData> ctx) {}

                        @Override
                        public void cancel() {}
                    },
                    true);
        }

        @Override
        public Map<String, DataType> listReadableMetadata() {
            final Map<String, DataType> metadata = new HashMap<>();
            metadata.put("m1", STRING());
            metadata.put("m2", STRING());
            metadata.put("m3", STRING());
            return metadata;
        }

        @Override
        public void applyReadableMetadata(List<String> metadataKeys, DataType producedDataType) {
            MetadataNoProjectionPushDownTableFactory.appliedMetadataKeys.set(metadataKeys);
        }

        @Override
        public boolean supportsMetadataProjection() {
            return options.get(SUPPORTS_METADATA_PROJECTION);
        }

        @Override
        public DynamicTableSource copy() {
            return new Source(options);
        }

        @Override
        public String asSummaryString() {
            return getClass().getName();
        }
    }
}
