<!--
 Copyright 2024-2026 the original author or authors.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<template>
	<section class="page-shell metadata-page">
		<header class="page-header">
			<div>
				<h1 class="text-h4 font-weight-bold mb-1">数据库元数据</h1>
				<p class="text-body-2 text-medium-emphasis mb-0">
					查看当前智能体可用的表结构、业务注释和字段样例。
				</p>
			</div>
			<div class="header-actions">
				<v-btn
					variant="outlined"
					prepend-icon="mdi-refresh"
					:loading="loading"
					:disabled="!agentId || regenerating"
					@click="loadMetadata"
				>
					刷新
				</v-btn>
				<v-btn
					color="primary"
					prepend-icon="mdi-database-sync"
					:loading="regenerating"
					:disabled="!agentId || loading"
					@click="confirmRegenerate"
				>
					重新生成元数据
				</v-btn>
			</div>
		</header>

		<v-alert v-if="!agentId" type="info" variant="tonal" class="mb-6">
			请先从顶部选择一个智能体。
		</v-alert>

		<div v-else-if="loading && !metadata" class="loading-state">
			<v-progress-circular indeterminate color="primary" size="36" />
			<span class="text-body-2 text-medium-emphasis"
				>正在读取数据库元数据...</span
			>
		</div>

		<v-alert
			v-else-if="errorMessage"
			type="error"
			variant="tonal"
			class="mb-6"
			closable
			@click:close="errorMessage = ''"
		>
			{{ errorMessage }}
		</v-alert>

		<template v-if="metadata">
			<div class="summary-band">
				<div class="summary-item">
					<span class="summary-label">已选择表</span>
					<strong>{{ metadata.selectedTableCount }}</strong>
				</div>
				<div class="summary-item">
					<span class="summary-label">已生成表</span>
					<strong>{{ metadata.loadedTableCount }}</strong>
				</div>
				<div class="summary-item summary-status">
					<span class="summary-label">元数据状态</span>
					<v-chip
						:size="'small'"
						:color="metadata.metadataReady ? 'success' : 'warning'"
						variant="tonal"
					>
						{{ metadata.metadataReady ? '完整' : '待更新' }}
					</v-chip>
				</div>
			</div>

			<v-alert
				v-if="metadata.missingTables.length"
				type="warning"
				variant="tonal"
				class="mb-5"
			>
				以下已选表尚无元数据：{{ metadata.missingTables.join('、') }}
			</v-alert>

			<div v-if="metadata.selectedTableCount === 0" class="empty-state">
				<v-icon icon="mdi-table-off" size="44" color="grey" />
				<h2 class="text-subtitle-1 font-weight-bold">尚未选择数据表</h2>
				<p class="text-body-2 text-medium-emphasis mb-0">
					请先在数据连接页面为当前智能体选择数据表。
				</p>
			</div>

			<div v-else-if="metadata.tables.length === 0" class="empty-state">
				<v-icon icon="mdi-database-alert-outline" size="44" color="warning" />
				<h2 class="text-subtitle-1 font-weight-bold">尚未生成元数据</h2>
				<p class="text-body-2 text-medium-emphasis mb-0">
					点击“重新生成元数据”读取当前数据源的表结构和注释。
				</p>
			</div>

			<template v-else>
				<div class="metadata-workspace">
					<aside class="table-browser">
						<v-text-field
							v-model="search"
							prepend-inner-icon="mdi-magnify"
							placeholder="搜索表或字段"
							variant="outlined"
							density="compact"
							hide-details
							clearable
							class="mb-3"
						/>
						<div class="table-count text-caption text-medium-emphasis">
							{{ filteredTables.length }} 张表
						</div>
						<nav class="table-list" aria-label="数据库表列表">
							<button
								v-for="table in filteredTables"
								:key="table.tableName"
								type="button"
								class="table-list-item"
								:class="{ active: selectedTableName === table.tableName }"
								@click="selectedTableName = table.tableName"
							>
								<span class="table-display-name">{{ table.displayName }}</span>
								<code>{{ table.tableName }}</code>
								<span class="text-caption text-medium-emphasis">
									{{ table.columns.length }} 个字段
								</span>
							</button>
						</nav>
						<div v-if="filteredTables.length === 0" class="table-list-empty">
							未找到匹配的表或字段
						</div>
					</aside>

					<main v-if="selectedTable" class="field-browser">
						<div class="table-heading">
							<div>
								<h2>{{ selectedTable.displayName }}</h2>
								<div class="physical-name">
									<code>{{ qualifiedTableName }}</code>
								</div>
							</div>
							<v-chip size="small" variant="outlined">
								{{ selectedTable.columns.length }} 个字段
							</v-chip>
						</div>
						<v-data-table
							:headers="columnHeaders"
							:items="selectedTable.columns"
							:items-per-page="25"
							:items-per-page-options="[10, 25, 50, -1]"
							density="comfortable"
							class="columns-table"
						>
							<template #item.displayName="{ item }">
								<div class="font-weight-medium">{{ item.displayName }}</div>
							</template>
							<template #item.columnName="{ item }">
								<code>{{ item.columnName }}</code>
							</template>
							<template #item.dataType="{ item }">
								<span class="data-type">{{ item.dataType || '-' }}</span>
							</template>
							<template #item.constraints="{ item }">
								<div class="constraint-list">
									<v-chip
										v-if="item.primaryKey"
										size="x-small"
										color="warning"
										variant="tonal"
										>PK</v-chip
									>
									<span class="text-caption">{{
										item.nullable ? '可空' : '非空'
									}}</span>
								</div>
							</template>
							<template #item.samples="{ item }">
								<div v-if="item.samples.length" class="sample-list">
									<code v-for="sample in item.samples" :key="sample">{{
										sample
									}}</code>
								</div>
								<span v-else class="text-medium-emphasis">-</span>
							</template>
						</v-data-table>
					</main>
				</div>

				<section class="relations-section">
					<div class="section-heading">
						<div>
							<h2 class="text-h6 font-weight-bold mb-1">表关系</h2>
							<p class="text-body-2 text-medium-emphasis mb-0">
								物理外键与人工配置的逻辑关系
							</p>
						</div>
						<span class="text-caption text-medium-emphasis"
							>{{ metadata.relations.length }} 条</span
						>
					</div>
					<v-data-table
						v-if="metadata.relations.length"
						:headers="relationHeaders"
						:items="metadata.relations"
						:items-per-page="10"
						density="comfortable"
					>
						<template #item.source="{ item }">
							<code>{{ item.sourceTable }}.{{ item.sourceColumn }}</code>
						</template>
						<template #item.target="{ item }">
							<code>{{ item.targetTable }}.{{ item.targetColumn }}</code>
						</template>
						<template #item.relationType="{ item }">
							<v-chip
								size="small"
								:color="
									item.relationType === 'PHYSICAL' ? 'primary' : 'success'
								"
								variant="tonal"
							>
								{{ item.relationType === 'PHYSICAL' ? '物理外键' : '逻辑关系' }}
							</v-chip>
						</template>
					</v-data-table>
					<div v-else class="relations-empty">暂无表关系</div>
				</section>
			</template>
		</template>
	</section>
</template>

<script setup lang="ts">
import agentDatasourceService, {
	type AgentSchemaMetadata,
	type SchemaTableMetadata,
} from '~/services/agentDatasource';

const route = useRoute();
const { showConfirm } = useConfirm();
const { $tip } = useNuxtApp();

const loading = ref(false);
const regenerating = ref(false);
const errorMessage = ref('');
const metadata = ref<AgentSchemaMetadata | null>(null);
const search = ref('');
const selectedTableName = ref('');

const agentId = computed(() => {
	const value = Number(route.query.agentId);
	return Number.isFinite(value) && value > 0 ? String(value) : '';
});

const filteredTables = computed(() => {
	const keyword = search.value.trim().toLocaleLowerCase();
	if (!keyword) return metadata.value?.tables ?? [];
	return (metadata.value?.tables ?? []).filter((table) => {
		return (
			table.tableName.toLocaleLowerCase().includes(keyword) ||
			table.tableComment.toLocaleLowerCase().includes(keyword) ||
			table.columns.some(
				(column) =>
					column.columnName.toLocaleLowerCase().includes(keyword) ||
					column.columnComment.toLocaleLowerCase().includes(keyword),
			)
		);
	});
});

const selectedTable = computed<SchemaTableMetadata | null>(() => {
	return (
		filteredTables.value.find(
			(table) => table.tableName === selectedTableName.value,
		) ??
		filteredTables.value[0] ??
		null
	);
});

const qualifiedTableName = computed(() => {
	if (!selectedTable.value) return '';
	return selectedTable.value.schemaName
		? `${selectedTable.value.schemaName}.${selectedTable.value.tableName}`
		: selectedTable.value.tableName;
});

const columnHeaders = [
	{ title: '中文含义', key: 'displayName', minWidth: 150 },
	{ title: '真实字段名', key: 'columnName', minWidth: 150 },
	{ title: '类型', key: 'dataType', width: 130 },
	{ title: '约束', key: 'constraints', width: 120, sortable: false },
	{ title: '脱敏样例', key: 'samples', minWidth: 220, sortable: false },
];

const relationHeaders = [
	{ title: '来源字段', key: 'source', minWidth: 220, sortable: false },
	{ title: '目标字段', key: 'target', minWidth: 220, sortable: false },
	{ title: '类型', key: 'relationType', width: 130 },
	{ title: '基数', key: 'cardinality', width: 100 },
	{ title: '说明', key: 'description', minWidth: 220 },
];

async function loadMetadata() {
	if (!agentId.value) {
		metadata.value = null;
		return;
	}
	loading.value = true;
	errorMessage.value = '';
	try {
		const response = await agentDatasourceService.getSchemaMetadata(
			agentId.value,
		);
		if (!response.success || !response.data) {
			throw new Error(response.message || '元数据响应为空');
		}
		metadata.value = response.data;
		const selectedStillExists = response.data.tables.some(
			(table) => table.tableName === selectedTableName.value,
		);
		if (!selectedStillExists)
			selectedTableName.value = response.data.tables[0]?.tableName ?? '';
	} catch (error: unknown) {
		metadata.value = null;
		errorMessage.value =
			error instanceof Error ? error.message : '获取数据库元数据失败';
	} finally {
		loading.value = false;
	}
}

function confirmRegenerate() {
	if (!agentId.value) return;
	showConfirm({
		title: '重新生成数据库元数据',
		message: '将直接读取业务数据库中当前已选表的结构、注释和样例，并保存为管理库快照。确定继续吗？',
		confirmText: '重新生成',
		icon: 'mdi-database-sync',
		onConfirm: regenerateMetadata,
	});
}

async function regenerateMetadata() {
	if (!agentId.value) return;
	regenerating.value = true;
	try {
		const response = await agentDatasourceService.refreshSchemaMetadata(agentId.value);
		if (!response.success) throw new Error(response.message || '重新生成失败');
		$tip('数据库元数据已重新生成');
		metadata.value = response.data ?? null;
		if (!metadata.value) await loadMetadata();
	} catch (error: unknown) {
		$tip(error instanceof Error ? error.message : '重新生成数据库元数据失败', {
			color: 'error',
			icon: 'mdi-alert-circle',
		});
	} finally {
		regenerating.value = false;
	}
}

watch(
	agentId,
	() => {
		search.value = '';
		selectedTableName.value = '';
		loadMetadata();
	},
	{ immediate: true },
);
</script>

<style scoped>
.metadata-page {
	color: #172033;
	min-width: 0;
	overflow-x: hidden;
}

.page-header,
.header-actions,
.summary-band,
.table-heading,
.section-heading,
.constraint-list,
.sample-list {
	display: flex;
	align-items: center;
}

.page-header,
.table-heading,
.section-heading {
	justify-content: space-between;
}

.page-header {
	gap: 24px;
	margin-bottom: 28px;
	flex-wrap: wrap;
}

.header-actions {
	gap: 12px;
	flex-shrink: 0;
}

.loading-state,
.empty-state {
	min-height: 280px;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	gap: 12px;
	border: 1px dashed #cbd5e1;
	background: #f8fafc;
}

.summary-band {
	gap: 0;
	margin-bottom: 20px;
	border-block: 1px solid #e2e8f0;
	background: #f8fafc;
}

.summary-item {
	min-width: 150px;
	padding: 16px 22px;
	display: grid;
	gap: 3px;
	border-right: 1px solid #e2e8f0;
}

.summary-item strong {
	font-size: 21px;
}

.summary-label,
.table-count {
	font-size: 12px;
	color: #64748b;
}

.summary-status {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 16px;
}

.metadata-workspace {
	display: grid;
	grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
	min-height: 560px;
	border: 1px solid #dbe3ec;
	background: #ffffff;
}

.table-browser {
	padding: 18px;
	border-right: 1px solid #dbe3ec;
	background: #f8fafc;
}

.table-count {
	padding: 0 4px 8px;
}

.table-list {
	display: grid;
	gap: 4px;
	max-height: 630px;
	overflow-y: auto;
}

.table-list-item {
	width: 100%;
	min-height: 72px;
	padding: 10px 12px;
	display: grid;
	gap: 2px;
	text-align: left;
	border: 1px solid transparent;
	background: transparent;
	cursor: pointer;
}

.table-list-item:hover {
	background: #eef3f7;
}

.table-list-item.active {
	border-color: #9fb4c8;
	background: #e7eef4;
}

.table-display-name {
	font-size: 14px;
	font-weight: 650;
	color: #172033;
	overflow-wrap: anywhere;
}

code {
	font-family: Consolas, 'Courier New', monospace;
	font-size: 12px;
	color: #334155;
	overflow-wrap: anywhere;
}

.table-list-empty,
.relations-empty {
	padding: 32px 12px;
	text-align: center;
	font-size: 13px;
	color: #64748b;
}

.field-browser {
	min-width: 0;
	padding: 24px;
	overflow-x: auto;
}

.table-heading {
	gap: 20px;
	margin-bottom: 12px;
}

.table-heading h2 {
	font-size: 20px;
	line-height: 1.35;
	margin: 0 0 4px;
}

.physical-name {
	color: #64748b;
}

.columns-table {
	border-top: 1px solid #e2e8f0;
	min-width: 760px;
}

.data-type {
	font-family: Consolas, 'Courier New', monospace;
	font-size: 12px;
	color: #0f766e;
}

.constraint-list,
.sample-list {
	flex-wrap: wrap;
	gap: 6px;
}

.sample-list code {
	display: inline-block;
	max-width: 180px;
	padding: 2px 6px;
	background: #f1f5f9;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.relations-section {
	margin-top: 34px;
	border-top: 1px solid #dbe3ec;
	padding-top: 24px;
	max-width: 100%;
	overflow-x: auto;
}

.section-heading {
	gap: 20px;
	margin-bottom: 14px;
}

@media (max-width: 900px) {
	.page-header {
		align-items: flex-start;
		flex-direction: column;
	}

	.header-actions {
		width: 100%;
		flex-wrap: wrap;
	}

	.summary-band {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}

	.summary-item {
		min-width: 0;
		border-bottom: 1px solid #e2e8f0;
	}

	.summary-status {
		grid-column: 1 / -1;
	}

	.metadata-workspace {
		grid-template-columns: minmax(0, 1fr);
	}

	.table-browser {
		border-right: 0;
		border-bottom: 1px solid #dbe3ec;
	}

	.table-list {
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		max-height: 240px;
	}

	.field-browser {
		padding: 18px 14px;
	}
}

@media (max-width: 520px) {
	.header-actions > * {
		flex: 1 1 100%;
	}

	.summary-band {
		grid-template-columns: minmax(0, 1fr);
	}

	.summary-status {
		grid-column: auto;
	}

	.table-heading {
		align-items: flex-start;
	}
}
</style>
