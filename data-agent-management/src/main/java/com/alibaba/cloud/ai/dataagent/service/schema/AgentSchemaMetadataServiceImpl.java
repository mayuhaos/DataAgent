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
import com.alibaba.cloud.ai.dataagent.util.SchemaSampleMasker;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentSchemaMetadataServiceImpl implements AgentSchemaMetadataService {

	private final AgentDatasourceService agentDatasourceService;

	private final DatasourceService datasourceService;

	private final AccessorFactory accessorFactory;

	private final TableMetadataService tableMetadataService;

	private final AgentSchemaMetadataSnapshotMapper snapshotMapper;

	@Override
	public AgentSchemaMetadataDTO getMetadata(Long agentId) {
		AgentDatasource binding = getBinding(agentId);
		List<String> selectedTables = selectedTables(binding);
		AgentSchemaMetadataSnapshot snapshot = snapshotMapper.selectByAgentId(agentId);
		if (snapshot == null || !Objects.equals(snapshot.getDatasourceId(), binding.getDatasourceId())) {
			return emptyMetadata(agentId, binding.getDatasourceId(), selectedTables);
		}
		try {
			AgentSchemaMetadataDTO stored = JsonUtil.getObjectMapper()
				.readValue(snapshot.getMetadataJson(), AgentSchemaMetadataDTO.class);
			return filterToCurrentSelection(stored, agentId, binding.getDatasourceId(), selectedTables);
		}
		catch (Exception e) {
			throw new IllegalStateException("Stored metadata snapshot cannot be parsed", e);
		}
	}

	@Override
	@Transactional
	public AgentSchemaMetadataDTO refreshMetadata(Long agentId) {
		AgentDatasource binding = getBinding(agentId);
		Integer datasourceId = binding.getDatasourceId();
		List<String> selectedTables = selectedTables(binding);
		if (selectedTables.isEmpty()) {
			throw new IllegalStateException("当前智能体尚未选择数据表");
		}

		Datasource datasource = Objects.requireNonNull(datasourceService.getDatasourceById(datasourceId),
				"Datasource does not exist");
		DbConfigBO dbConfig = datasourceService.getDbConfig(datasource);
		Accessor accessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		DbQueryParameter query = DbQueryParameter.from(dbConfig)
			.setSchema(dbConfig.getSchema())
			.setTables(selectedTables);
		try {
			List<ForeignKeyInfoBO> physicalForeignKeys = accessor.showForeignKeys(dbConfig, query);
			List<TableInfoBO> tables = accessor.fetchTables(dbConfig, query)
				.stream()
				.filter(table -> selectedTables.contains(table.getName()))
				.toList();
			tableMetadataService.batchEnrichTableMetadata(tables, dbConfig,
					buildForeignKeyMap(physicalForeignKeys));
			AgentSchemaMetadataDTO metadata = buildMetadata(agentId, datasourceId, selectedTables, tables,
					physicalForeignKeys, datasourceService.getLogicalRelations(datasourceId));
			persistSnapshot(metadata);
			return metadata;
		}
		catch (Exception e) {
			throw new IllegalStateException("同步数据库元数据失败: " + e.getMessage(), e);
		}
	}

	private void persistSnapshot(AgentSchemaMetadataDTO metadata) throws Exception {
		AgentSchemaMetadataSnapshot snapshot = AgentSchemaMetadataSnapshot.builder()
			.agentId(metadata.getAgentId())
			.datasourceId(metadata.getDatasourceId())
			.metadataJson(JsonUtil.getObjectMapper().writeValueAsString(metadata))
			.generatedAt(LocalDateTime.now())
			.build();
		snapshotMapper.deleteByAgentId(metadata.getAgentId());
		snapshotMapper.insert(snapshot);
	}

	private AgentSchemaMetadataDTO buildMetadata(Long agentId, Integer datasourceId, List<String> selectedTables,
			List<TableInfoBO> tableInfos, List<ForeignKeyInfoBO> physicalForeignKeys,
			List<LogicalRelation> logicalRelations) {
		List<TableMetadata> tables = tableInfos.stream()
			.map(this::toTableMetadata)
			.sorted(Comparator.comparing(TableMetadata::getTableName, String.CASE_INSENSITIVE_ORDER))
			.toList();
		Set<String> loadedNames = tables.stream().map(TableMetadata::getTableName).collect(Collectors.toSet());
		List<String> missingTables = selectedTables.stream().filter(name -> !loadedNames.contains(name)).toList();
		Set<String> selectedSet = new LinkedHashSet<>(selectedTables);
		List<RelationMetadata> relations = buildRelations(physicalForeignKeys, logicalRelations, selectedSet);
		return AgentSchemaMetadataDTO.builder()
			.agentId(agentId)
			.datasourceId(datasourceId)
			.metadataReady(missingTables.isEmpty())
			.selectedTableCount(selectedTables.size())
			.loadedTableCount(tables.size())
			.missingTables(missingTables)
			.tables(tables)
			.relations(relations)
			.build();
	}

	private TableMetadata toTableMetadata(TableInfoBO table) {
		String comment = StringUtils.defaultString(table.getDescription());
		List<String> primaryKeys = Objects.requireNonNullElse(table.getPrimaryKeys(), List.of());
		List<ColumnMetadata> columns = Objects.requireNonNullElse(table.getColumns(), List.<ColumnInfoBO>of())
			.stream()
			.map(column -> toColumnMetadata(column, primaryKeys))
			.sorted(Comparator.comparing(ColumnMetadata::getColumnName, String.CASE_INSENSITIVE_ORDER))
			.toList();
		return TableMetadata.builder()
			.schemaName(StringUtils.defaultString(table.getSchema()))
			.tableName(table.getName())
			.tableComment(comment)
			.displayName(StringUtils.defaultIfBlank(comment, table.getName()))
			.primaryKeys(primaryKeys)
			.columns(columns)
			.build();
	}

	private ColumnMetadata toColumnMetadata(ColumnInfoBO column, List<String> primaryKeys) {
		String comment = StringUtils.defaultString(column.getDescription());
		return ColumnMetadata.builder()
			.columnName(column.getName())
			.columnComment(comment)
			.displayName(StringUtils.defaultIfBlank(comment, column.getName()))
			.dataType(StringUtils.defaultString(column.getType()))
			.primaryKey(column.isPrimary() || primaryKeys.contains(column.getName()))
			.nullable(!column.isNotnull())
			.samples(SchemaSampleMasker.mask(column.getName() + " " + comment, parseSamples(column.getSamples())))
			.build();
	}

	private List<RelationMetadata> buildRelations(List<ForeignKeyInfoBO> physicalForeignKeys,
			List<LogicalRelation> logicalRelations, Set<String> selectedTables) {
		Map<String, RelationMetadata> relations = new HashMap<>();
		for (ForeignKeyInfoBO relation : Objects.requireNonNullElse(physicalForeignKeys, List.<ForeignKeyInfoBO>of())) {
			if (!selectedTables.contains(relation.getTable())
					|| !selectedTables.contains(relation.getReferencedTable())) {
				continue;
			}
			RelationMetadata metadata = RelationMetadata.builder()
				.sourceTable(relation.getTable())
				.sourceColumn(relation.getColumn())
				.targetTable(relation.getReferencedTable())
				.targetColumn(relation.getReferencedColumn())
				.relationType(RelationKind.PHYSICAL)
				.build();
			relations.put(relationKey(metadata), metadata);
		}
		for (LogicalRelation relation : Objects.requireNonNullElse(logicalRelations, List.<LogicalRelation>of())) {
			if (!selectedTables.contains(relation.getSourceTableName())
					|| !selectedTables.contains(relation.getTargetTableName())) {
				continue;
			}
			RelationMetadata metadata = RelationMetadata.builder()
				.sourceTable(relation.getSourceTableName())
				.sourceColumn(relation.getSourceColumnName())
				.targetTable(relation.getTargetTableName())
				.targetColumn(relation.getTargetColumnName())
				.relationType(RelationKind.LOGICAL)
				.cardinality(relation.getRelationType())
				.description(relation.getDescription())
				.build();
			relations.put(relationKey(metadata), metadata);
		}
		return relations.values()
			.stream()
			.sorted(Comparator.comparing(this::relationKey, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	private AgentSchemaMetadataDTO filterToCurrentSelection(AgentSchemaMetadataDTO stored, Long agentId,
			Integer datasourceId, List<String> selectedTables) {
		Set<String> selectedSet = new LinkedHashSet<>(selectedTables);
		List<TableMetadata> tables = Objects.requireNonNullElse(stored.getTables(), List.<TableMetadata>of())
			.stream()
			.filter(table -> selectedSet.contains(table.getTableName()))
			.toList();
		Set<String> loadedNames = tables.stream().map(TableMetadata::getTableName).collect(Collectors.toSet());
		List<String> missingTables = selectedTables.stream().filter(name -> !loadedNames.contains(name)).toList();
		List<RelationMetadata> relations = Objects
			.requireNonNullElse(stored.getRelations(), List.<RelationMetadata>of())
			.stream()
			.filter(relation -> selectedSet.contains(relation.getSourceTable())
					&& selectedSet.contains(relation.getTargetTable()))
			.toList();
		return AgentSchemaMetadataDTO.builder()
			.agentId(agentId)
			.datasourceId(datasourceId)
			.metadataReady(!selectedTables.isEmpty() && missingTables.isEmpty())
			.selectedTableCount(selectedTables.size())
			.loadedTableCount(tables.size())
			.missingTables(missingTables)
			.tables(tables)
			.relations(relations)
			.build();
	}

	private AgentSchemaMetadataDTO emptyMetadata(Long agentId, Integer datasourceId, List<String> selectedTables) {
		return AgentSchemaMetadataDTO.builder()
			.agentId(agentId)
			.datasourceId(datasourceId)
			.metadataReady(false)
			.selectedTableCount(selectedTables.size())
			.loadedTableCount(0)
			.missingTables(selectedTables)
			.tables(List.of())
			.relations(List.of())
			.build();
	}

	private AgentDatasource getBinding(Long agentId) {
		AgentDatasource binding = agentDatasourceService.getCurrentAgentDatasource(agentId);
		Objects.requireNonNull(binding.getDatasourceId(), "Datasource ID cannot be null");
		return binding;
	}

	private List<String> selectedTables(AgentDatasource binding) {
		return new ArrayList<>(new LinkedHashSet<>(Objects.requireNonNullElse(binding.getSelectTables(), List.of())));
	}

	private Map<String, List<String>> buildForeignKeyMap(List<ForeignKeyInfoBO> foreignKeys) {
		Map<String, List<String>> result = new HashMap<>();
		for (ForeignKeyInfoBO foreignKey : Objects.requireNonNullElse(foreignKeys, List.<ForeignKeyInfoBO>of())) {
			String value = foreignKey.getTable() + "." + foreignKey.getColumn() + "="
					+ foreignKey.getReferencedTable() + "." + foreignKey.getReferencedColumn();
			result.computeIfAbsent(foreignKey.getTable(), ignored -> new ArrayList<>()).add(value);
			result.computeIfAbsent(foreignKey.getReferencedTable(), ignored -> new ArrayList<>()).add(value);
		}
		return result;
	}

	private List<String> parseSamples(String samples) {
		if (StringUtils.isBlank(samples)) {
			return List.of();
		}
		try {
			return JsonUtil.getObjectMapper().readValue(samples, new TypeReference<List<String>>() {
			});
		}
		catch (Exception ignored) {
			return List.of();
		}
	}

	private String relationKey(RelationMetadata relation) {
		return String.join("|", relation.getSourceTable(), relation.getSourceColumn(), relation.getTargetTable(),
				relation.getTargetColumn(), relation.getRelationType().name());
	}

}
