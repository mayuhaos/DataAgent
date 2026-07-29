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
package com.alibaba.cloud.ai.dataagent.dto.schema;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Data
@Builder
@Jacksonized
public class AgentSchemaMetadataDTO {

	private Long agentId;

	private Integer datasourceId;

	private boolean metadataReady;

	private int selectedTableCount;

	private int loadedTableCount;

	private List<String> missingTables;

	private List<TableMetadata> tables;

	private List<RelationMetadata> relations;

	@Data
	@Builder
	@Jacksonized
	public static class TableMetadata {

		private String schemaName;

		private String tableName;

		private String tableComment;

		private String displayName;

		private List<String> primaryKeys;

		private List<ColumnMetadata> columns;

	}

	@Data
	@Builder
	@Jacksonized
	public static class ColumnMetadata {

		private String columnName;

		private String columnComment;

		private String displayName;

		private String dataType;

		private boolean primaryKey;

		private boolean nullable;

		private List<String> samples;

	}

	@Data
	@Builder
	@Jacksonized
	public static class RelationMetadata {

		private String sourceTable;

		private String sourceColumn;

		private String targetTable;

		private String targetColumn;

		private RelationKind relationType;

		private String cardinality;

		private String description;

	}

	public enum RelationKind {

		PHYSICAL,

		LOGICAL

	}

}
