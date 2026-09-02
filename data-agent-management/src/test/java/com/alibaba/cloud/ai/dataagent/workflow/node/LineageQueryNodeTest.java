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
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.DATA_LINEAGE_SOURCES;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.LINEAGE_QUERY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineageQueryNodeTest {

	@Mock
	private DatabaseUtil databaseUtil;

	@Mock
	private LineageQueryService lineageQueryService;

	@Mock
	private Accessor accessor;

	private LineageQueryNode node;

	@BeforeEach
	void setUp() {
		node = new LineageQueryNode(databaseUtil, lineageQueryService);
	}

	@Test
	@SuppressWarnings("unchecked")
	void apply_queriesAndStoresDiscoveredSources() {
		OverAllState state = new OverAllState();
		state.registerKeyAndStrategy(SQL_GENERATE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(AGENT_ID, new ReplaceStrategy());
		state.registerKeyAndStrategy(DATA_LINEAGE_SOURCES, new ReplaceStrategy());
		state.updateState(Map.of(SQL_GENERATE_OUTPUT, "SELECT * FROM complaint_event", AGENT_ID, "1"));
		DbConfigBO config = DbConfigBO.builder().dialectType("mysql").build();
		Map<String, String> source = Map.of("source_file_name", "complaint.xlsx", "source_file_sha256", "abc");
		when(databaseUtil.getAgentDbConfig(1L)).thenReturn(config);
		when(databaseUtil.getAgentAccessor(1L)).thenReturn(accessor);
		when(databaseUtil.getAgentDatasourceId(1L)).thenReturn(4);
		when(lineageQueryService.querySources(any(), any(), any(), any())).thenReturn(List.of(source));

		Map<String, Object> result = node.apply(state);
		List<GraphResponse<StreamingOutput>> responses = ((Flux<GraphResponse<StreamingOutput>>) result
			.get(LINEAGE_QUERY_NODE_OUTPUT)).collectList().block();
		Map<String, Object> completion = (Map<String, Object>) responses.get(responses.size() - 1)
			.resultValue()
			.orElseThrow();

		assertEquals(List.of(source), completion.get(DATA_LINEAGE_SOURCES));
		assertTrue(responses.stream()
			.filter(response -> !response.isDone())
			.anyMatch(response -> response.getOutput().join().chunk().contains("数据来源查询完成")));
	}

}
