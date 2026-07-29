/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.schema;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ForeignKeyInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.TableInfoBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.connector.accessor.AccessorFactory;
import com.alibaba.cloud.ai.dataagent.dto.schema.AgentSchemaMetadataDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.AgentSchemaMetadataDTO.ColumnMetadata;
import com.alibaba.cloud.ai.dataagent.dto.schema.AgentSchemaMetadataDTO.RelationKind;
import com.alibaba.cloud.ai.dataagent.dto.schema.AgentSchemaMetadataDTO.RelationMetadata;
import com.alibaba.cloud.ai.dataagent.dto.schema.AgentSchemaMetadataDTO.TableMetadata;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.entity.AgentSchemaMetadataSnapshot;
import com.alibaba.cloud.ai.dataagent.entity.Datasource;
import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import com.alibaba.cloud.ai.dataagent.mapper.AgentSchemaMetadataSnapshotMapper;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.service.datasource.DatasourceService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSchemaMetadataServiceImplTest {

	@Mock
	private AgentDatasourceService agentDatasourceService;

	@Mock
	private DatasourceService datasourceService;

	@Mock
	private AccessorFactory accessorFactory;

	@Mock
	private Accessor accessor;

	@Mock
	private TableMetadataService tableMetadataService;

	@Mock
	private AgentSchemaMetadataSnapshotMapper snapshotMapper;

	private AgentSchemaMetadataServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new AgentSchemaMetadataServiceImpl(agentDatasourceService, datasourceService, accessorFactory,
				tableMetadataService, snapshotMapper);
	}

	@Test
	void returnsMissingTablesWhenSnapshotDoesNotExist() {
		mockBinding(7L, 3, List.of("orders", "users"));

		AgentSchemaMetadataDTO result = service.getMetadata(7L);

		assertFalse(result.isMetadataReady());
		assertEquals(List.of("orders", "users"), result.getMissingTables());
		assertTrue(result.getTables().isEmpty());
		assertEquals(2, result.getSelectedTableCount());
	}

	@Test
	void readsSnapshotAndFiltersItToCurrentTableSelection() throws Exception {
		mockBinding(7L, 3, List.of("orders", "new_table"));
		AgentSchemaMetadataDTO stored = AgentSchemaMetadataDTO.builder()
			.agentId(7L)
			.datasourceId(3)
			.metadataReady(true)
			.selectedTableCount(2)
			.loadedTableCount(2)
			.missingTables(List.of())
			.tables(List.of(tableMetadata("orders", "销售订单"), tableMetadata("users", "用户")))
			.relations(List.of(relation("orders", "user_id", "users", "id", RelationKind.PHYSICAL)))
			.build();
		when(snapshotMapper.selectByAgentId(7L)).thenReturn(snapshot(7L, 3, stored));

		AgentSchemaMetadataDTO result = service.getMetadata(7L);

		assertFalse(result.isMetadataReady());
		assertEquals(List.of("new_table"), result.getMissingTables());
		assertEquals(List.of("orders"), result.getTables().stream().map(TableMetadata::getTableName).toList());
		assertTrue(result.getRelations().isEmpty());
	}

	@Test
	void ignoresSnapshotCreatedForAnotherDatasource() throws Exception {
		mockBinding(7L, 4, List.of("orders"));
		AgentSchemaMetadataDTO stored = AgentSchemaMetadataDTO.builder()
			.agentId(7L)
			.datasourceId(3)
			.tables(List.of(tableMetadata("orders", "销售订单")))
			.relations(List.of())
			.build();
		when(snapshotMapper.selectByAgentId(7L)).thenReturn(snapshot(7L, 3, stored));

		AgentSchemaMetadataDTO result = service.getMetadata(7L);

		assertFalse(result.isMetadataReady());
		assertTrue(result.getTables().isEmpty());
		assertEquals(List.of("orders"), result.getMissingTables());
	}

	@Test
	void refreshesDirectlyFromDatabaseAndPersistsMaskedSnapshot() throws Exception {
		List<String> selectedTables = List.of("orders", "users", "missing_table");
		mockBinding(7L, 3, selectedTables);
		Datasource datasource = Datasource.builder().id(3).name("业务库").build();
		DbConfigBO dbConfig = DbConfigBO.builder().schema("public").build();
		when(datasourceService.getDatasourceById(3)).thenReturn(datasource);
		when(datasourceService.getDbConfig(datasource)).thenReturn(dbConfig);
		when(accessorFactory.getAccessorByDbConfig(dbConfig)).thenReturn(accessor);

		ForeignKeyInfoBO physical = ForeignKeyInfoBO.builder()
			.table("orders")
			.column("user_id")
			.referencedTable("users")
			.referencedColumn("id")
			.build();
		when(accessor.showForeignKeys(eq(dbConfig), any(DbQueryParameter.class))).thenReturn(List.of(physical));
		when(accessor.fetchTables(eq(dbConfig), any(DbQueryParameter.class)))
			.thenReturn(List.of(orderTable(), userTable(), table("unselected", "不应返回", List.of())));
		when(datasourceService.getLogicalRelations(3))
			.thenReturn(List.of(LogicalRelation.builder()
				.sourceTableName("orders")
				.sourceColumnName("user_id")
				.targetTableName("users")
				.targetColumnName("id")
				.relationType("N:1")
				.description("订单所属用户")
				.build()));

		AgentSchemaMetadataDTO result = service.refreshMetadata(7L);

		assertFalse(result.isMetadataReady());
		assertEquals(List.of("missing_table"), result.getMissingTables());
		assertEquals(List.of("orders", "users"), result.getTables().stream().map(TableMetadata::getTableName).toList());
		assertEquals("销售订单", result.getTables().get(0).getDisplayName());
		ColumnMetadata mobile = findColumn(result, "orders", "mobile_phone");
		assertEquals(List.of("138***5678", "139***5678", "137***5678"), mobile.getSamples());
		assertTrue(findColumn(result, "users", "access_token").getSamples().isEmpty());
		assertEquals(List.of("张***"), findColumn(result, "users", "xm").getSamples());
		assertEquals(2, result.getRelations().size());
		assertTrue(result.getRelations().stream().anyMatch(item -> item.getRelationType() == RelationKind.PHYSICAL));
		assertTrue(result.getRelations().stream().anyMatch(item -> item.getRelationType() == RelationKind.LOGICAL));

		verify(tableMetadataService).batchEnrichTableMetadata(any(), eq(dbConfig), any());
		verify(snapshotMapper).deleteByAgentId(7L);
		ArgumentCaptor<AgentSchemaMetadataSnapshot> captor = ArgumentCaptor
			.forClass(AgentSchemaMetadataSnapshot.class);
		verify(snapshotMapper).insert(captor.capture());
		AgentSchemaMetadataSnapshot persisted = captor.getValue();
		assertEquals(7L, persisted.getAgentId());
		assertEquals(3, persisted.getDatasourceId());
		assertNotNull(persisted.getGeneratedAt());
		AgentSchemaMetadataDTO persistedMetadata = JsonUtil.getObjectMapper()
			.readValue(persisted.getMetadataJson(), AgentSchemaMetadataDTO.class);
		assertEquals(List.of("138***5678", "139***5678", "137***5678"),
				findColumn(persistedMetadata, "orders", "mobile_phone").getSamples());
		assertFalse(persisted.getMetadataJson().contains("raw-token-value"));
	}

	private void mockBinding(Long agentId, Integer datasourceId, List<String> tables) {
		AgentDatasource binding = new AgentDatasource(agentId, datasourceId);
		binding.setSelectTables(tables);
		when(agentDatasourceService.getCurrentAgentDatasource(agentId)).thenReturn(binding);
	}

	private AgentSchemaMetadataSnapshot snapshot(Long agentId, Integer datasourceId, AgentSchemaMetadataDTO metadata)
			throws Exception {
		return AgentSchemaMetadataSnapshot.builder()
			.agentId(agentId)
			.datasourceId(datasourceId)
			.metadataJson(JsonUtil.getObjectMapper().writeValueAsString(metadata))
			.build();
	}

	private TableInfoBO orderTable() {
		return table("orders", "销售订单",
				List.of(column("id", "订单编号", "bigint", true, true, "[\"1\"]"),
						column("mobile_phone", "联系电话", "varchar", false, false,
								"[\"13812345678\",\"13912345678\",\"13712345678\",\"13612345678\"]"),
						column("user_id", "用户编号", "bigint", false, true, "[\"10\"]")));
	}

	private TableInfoBO userTable() {
		return table("users", "", List.of(column("id", "", "bigint", true, true, "[\"10\"]"),
				column("xm", "姓名", "varchar", false, false, "[\"张三\"]"),
				column("access_token", "访问令牌", "varchar", false, false, "[\"raw-token-value\"]")));
	}

	private TableInfoBO table(String name, String description, List<ColumnInfoBO> columns) {
		return TableInfoBO.builder()
			.schema("public")
			.name(name)
			.description(description)
			.primaryKeys(List.of("id"))
			.columns(columns)
			.build();
	}

	private ColumnInfoBO column(String name, String description, String type, boolean primary, boolean notnull,
			String samples) {
		return ColumnInfoBO.builder()
			.name(name)
			.description(description)
			.type(type)
			.primary(primary)
			.notnull(notnull)
			.samples(samples)
			.build();
	}

	private TableMetadata tableMetadata(String name, String comment) {
		return TableMetadata.builder()
			.schemaName("public")
			.tableName(name)
			.tableComment(comment)
			.displayName(comment)
			.primaryKeys(List.of("id"))
			.columns(List.of())
			.build();
	}

	private RelationMetadata relation(String sourceTable, String sourceColumn, String targetTable,
			String targetColumn, RelationKind kind) {
		return RelationMetadata.builder()
			.sourceTable(sourceTable)
			.sourceColumn(sourceColumn)
			.targetTable(targetTable)
			.targetColumn(targetColumn)
			.relationType(kind)
			.build();
	}

	private ColumnMetadata findColumn(AgentSchemaMetadataDTO metadata, String tableName, String columnName) {
		return metadata.getTables()
			.stream()
			.filter(table -> tableName.equals(table.getTableName()))
			.flatMap(table -> table.getColumns().stream())
			.filter(column -> columnName.equals(column.getColumnName()))
			.findFirst()
			.orElseThrow();
	}

}
