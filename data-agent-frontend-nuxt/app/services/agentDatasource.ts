/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { $fetch } from 'ofetch';

export interface ApiResponse<T> {
	success: boolean;
	message?: string;
	data?: T;
}

export interface AgentDatasource {
	id?: number;
	agentId?: number;
	datasourceId?: number;
	isActive?: number;
	selectTables?: string[];
	datasource?: unknown;
}

export interface UpdateDatasourceTablesDto {
	datasourceId?: number;
	tables?: string[];
}

export interface SchemaColumnMetadata {
	columnName: string;
	columnComment: string;
	displayName: string;
	dataType: string;
	primaryKey: boolean;
	nullable: boolean;
	samples: string[];
}

export interface SchemaTableMetadata {
	schemaName: string;
	tableName: string;
	tableComment: string;
	displayName: string;
	primaryKeys: string[];
	columns: SchemaColumnMetadata[];
}

export interface SchemaRelationMetadata {
	sourceTable: string;
	sourceColumn: string;
	targetTable: string;
	targetColumn: string;
	relationType: 'PHYSICAL' | 'LOGICAL';
	cardinality?: string;
	description?: string;
}

export interface AgentSchemaMetadata {
	agentId: number;
	datasourceId: number;
	metadataReady: boolean;
	selectedTableCount: number;
	loadedTableCount: number;
	missingTables: string[];
	tables: SchemaTableMetadata[];
	relations: SchemaRelationMetadata[];
}

const BASE_URL_FUNC = (agentId: string) => `/api/agent/${agentId}/datasources`;

class AgentDatasourceService {
	/**
	 * 初始化数据源Schema
	 * @param agentId 智能体ID
	 */
	async initSchema(agentId: string): Promise<ApiResponse<null>> {
		return await $fetch<ApiResponse<null>>(`${BASE_URL_FUNC(agentId)}/init`, {
			method: 'POST',
		});
	}

	/**
	 * 获取当前激活的智能体数据源
	 * @param agentId 智能体ID
	 */
	async getActiveAgentDatasource(agentId: string): Promise<ApiResponse<AgentDatasource>> {
		return await $fetch<ApiResponse<AgentDatasource>>(
			`${BASE_URL_FUNC(agentId)}/active`,
		);
	}

	/**
	 * 获取当前智能体保存在管理库中的数据库元数据快照。
	 */
	async getSchemaMetadata(
		agentId: string,
	): Promise<ApiResponse<AgentSchemaMetadata>> {
		return await $fetch<ApiResponse<AgentSchemaMetadata>>(
			`${BASE_URL_FUNC(agentId)}/metadata`,
		);
	}

	/**
	 * 直接从业务数据库同步当前智能体已选表的元数据，并保存管理库快照。
	 */
	async refreshSchemaMetadata(
		agentId: string,
	): Promise<ApiResponse<AgentSchemaMetadata>> {
		return await $fetch<ApiResponse<AgentSchemaMetadata>>(
			`${BASE_URL_FUNC(agentId)}/metadata/refresh`,
			{ method: 'POST' },
		);
	}

	/**
	 * 为智能体添加数据源关联
	 * @param agentId 智能体ID
	 * @param datasourceId 数据源ID
	 */
	async addDatasourceToAgent(agentId: string, datasourceId: number): Promise<ApiResponse<AgentDatasource>> {
		return await $fetch<ApiResponse<AgentDatasource>>(`${BASE_URL_FUNC(agentId)}/${datasourceId}`, {
			method: 'POST',
		});
	}

	/**
	 * 更新智能体数据源选中的表
	 * @param agentId 智能体ID
	 * @param dto 更新参数
	 */
	async updateDatasourceTables(agentId: string, dto: UpdateDatasourceTablesDto): Promise<ApiResponse<null>> {
		return await $fetch<ApiResponse<null>>(`${BASE_URL_FUNC(agentId)}/tables`, {
			method: 'POST',
			body: dto,
		});
	}
}

export default new AgentDatasourceService();
