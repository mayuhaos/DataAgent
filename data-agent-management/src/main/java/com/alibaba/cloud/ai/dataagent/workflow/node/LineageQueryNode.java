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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.service.lineage.LineageQueryService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.DATA_LINEAGE_SOURCES;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.LINEAGE_QUERY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;

/** Queries source-lineage records after the user SQL has completed. */
@Slf4j
@Component
@AllArgsConstructor
public class LineageQueryNode implements NodeAction {

	private final DatabaseUtil databaseUtil;

	private final LineageQueryService lineageQueryService;

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, Object> apply(OverAllState state) {
		String sqlQuery = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		String agentIdValue = StateUtil.getStringValue(state, AGENT_ID);
		Map<String, Object> result = new LinkedHashMap<>();

		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("正在查询数据来源..."));
			try {
				if (StringUtils.isBlank(sqlQuery) || StringUtils.isBlank(agentIdValue)) {
					emitter.next(ChatResponseUtil.createResponse("未获取到可追溯的SQL，跳过来源查询。"));
					return;
				}
				Long agentId = Long.valueOf(agentIdValue);
				DbConfigBO dbConfig = databaseUtil.getAgentDbConfig(agentId);
				Accessor accessor = databaseUtil.getAgentAccessor(agentId);
				Integer datasourceId = databaseUtil.getAgentDatasourceId(agentId);
				List<Map<String, String>> currentSources = StateUtil.getObjectValue(state, DATA_LINEAGE_SOURCES,
						List.class, List.of());
				List<Map<String, String>> discoveredSources = lineageQueryService.querySources(datasourceId, dbConfig,
						accessor, sqlQuery);
				List<Map<String, String>> mergedSources = mergeSources(currentSources, discoveredSources);
				result.put(DATA_LINEAGE_SOURCES, mergedSources);
				emitter.next(ChatResponseUtil.createResponse("数据来源查询完成，识别到 " + discoveredSources.size() + " 个来源。"));
			}
			catch (Exception exception) {
				// Source attribution is supplementary and must not make a successful SQL step fail.
				log.warn("Failed to query data lineage sources", exception);
				emitter.next(ChatResponseUtil.createResponse("数据来源查询失败，已跳过。"));
			}
			finally {
				emitter.complete();
			}
		});

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, value -> result, displayFlux);
		return Map.of(LINEAGE_QUERY_NODE_OUTPUT, generator);
	}

	private List<Map<String, String>> mergeSources(List<Map<String, String>> currentSources,
			List<Map<String, String>> discoveredSources) {
		Map<String, Map<String, String>> merged = new LinkedHashMap<>();
		for (Map<String, String> source : Optional.ofNullable(currentSources).orElseGet(List::of)) {
			merged.put(sourceKey(source), source);
		}
		for (Map<String, String> source : Optional.ofNullable(discoveredSources).orElseGet(List::of)) {
			merged.putIfAbsent(sourceKey(source), source);
		}
		return new ArrayList<>(merged.values());
	}

	private String sourceKey(Map<String, String> source) {
		return source.getOrDefault("source_resource_id", "") + "|"
				+ source.getOrDefault("source_file_sha256", "") + "|"
				+ source.getOrDefault("source_sheet", "");
	}

}
