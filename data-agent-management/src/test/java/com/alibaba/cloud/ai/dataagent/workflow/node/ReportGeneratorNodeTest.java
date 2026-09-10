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

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.dataagent.common.TestFixtures;
import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.entity.UserPromptConfig;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.prompt.UserPromptService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportGeneratorNodeTest {

	@Mock
	private LlmService llmService;

	@Mock
	private UserPromptService promptConfigService;

	private ReportGeneratorNode reportGeneratorNode;

	@BeforeEach
	void setUp() {
		reportGeneratorNode = new ReportGeneratorNode(llmService, promptConfigService);
	}

	private OverAllState createTestState() {
		OverAllState state = new OverAllState();
		state.registerKeyAndStrategy(PLANNER_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(QUERY_ENHANCE_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(PLAN_CURRENT_STEP, new ReplaceStrategy());
		state.registerKeyAndStrategy(SQL_EXECUTE_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(DATA_LINEAGE_SOURCES, new ReplaceStrategy());
		state.registerKeyAndStrategy(AGENT_ID, new ReplaceStrategy());
		state.registerKeyAndStrategy(RESULT, new ReplaceStrategy());
		return state;
	}

	private void setupBasicState(OverAllState state) {
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("查询用户数据");
		String planJson = TestFixtures.planToJson(TestFixtures.createPlan("分析用户数据",
				TestFixtures.createSqlStep(1, "查询用户"), TestFixtures.createReportStep(2, "生成用户数据分析报告")));

		HashMap<String, String> executionResults = new HashMap<>();
		executionResults.put("step_1", "[{\"id\":1,\"name\":\"张三\"},{\"id\":2,\"name\":\"李四\"}]");

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 2,
				SQL_EXECUTE_NODE_OUTPUT, executionResults, AGENT_ID, "1"));
	}

	@Test
	void apply_validData_returnsResultGenerator() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("<h1>用户数据分析报告</h1>")));

		Map<String, Object> result = reportGeneratorNode.apply(state);

		assertNotNull(result);
		assertTrue(result.containsKey(RESULT));
		verify(llmService).callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), eq(false));
	}

	@Test
	void apply_reportPromptDoesNotRequireTransportMarker() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("# Report")));

		reportGeneratorNode.apply(state);

		ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
		verify(llmService).callWithState(systemPrompt.capture(), anyString(), org.mockito.ArgumentMatchers.any(), eq(false));
		assertFalse(systemPrompt.getValue().contains("DATA_AGENT_FINAL_REPORT"));
	}

	@Test
	void apply_usesOnlyHighestPriorityReportOptimization() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		UserPromptConfig lowerPriority = UserPromptConfig.builder()
			.priority(0)
			.systemPrompt("LOWER_PRIORITY_CONFLICTING_TEMPLATE")
			.build();
		UserPromptConfig higherPriority = UserPromptConfig.builder()
			.priority(1)
			.systemPrompt("HIGHER_PRIORITY_REPORT_TEMPLATE")
			.build();
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(List.of(lowerPriority, higherPriority));
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("# Report")));

		reportGeneratorNode.apply(state);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llmService).callWithState(anyString(), prompt.capture(), org.mockito.ArgumentMatchers.any(), eq(false));
		assertTrue(prompt.getValue().contains("HIGHER_PRIORITY_REPORT_TEMPLATE"));
		assertFalse(prompt.getValue().contains("LOWER_PRIORITY_CONFLICTING_TEMPLATE"));
	}

	@Test
	void apply_doesNotIncludePlannerThoughtProcessInReportPrompt() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("# Report")));

		reportGeneratorNode.apply(state);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llmService).callWithState(anyString(), prompt.capture(), org.mockito.ArgumentMatchers.any(), eq(false));
		assertFalse(prompt.getValue().contains("**思考过程**"));
		assertTrue(prompt.getValue().contains("不包含内部推理"));
	}

	@Test
	void apply_plainMarkdownWithoutMarkerIsRetained() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("# Delivery report")));

		Map<String, Object> result = reportGeneratorNode.apply(state);
		@SuppressWarnings("unchecked")
		Flux<GraphResponse<StreamingOutput>> stream = (Flux<GraphResponse<StreamingOutput>>) result.get(RESULT);
		String output = stream.collectList().block().stream().filter(response -> !response.isDone()).map(response -> {
			try {
				return response.getOutput().get().chunk();
			}
			catch (Exception exception) {
				throw new AssertionError(exception);
			}
		}).reduce("", String::concat);

		assertTrue(output.contains("$$$markdown-report"));
		assertTrue(output.contains("# Delivery report"));
		assertFalse(output.contains("报告生成失败"));
		assertTrue(output.contains("$$$/markdown-report"));
	}

	@Test
	void apply_removesTaggedThinkingProcessWithoutRequiringMarker() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("<think>internal reasoning</think># Report")));

		Map<String, Object> result = reportGeneratorNode.apply(state);
		@SuppressWarnings("unchecked")
		Flux<GraphResponse<StreamingOutput>> stream = (Flux<GraphResponse<StreamingOutput>>) result.get(RESULT);
		String output = stream.collectList().block().stream().filter(response -> !response.isDone()).map(response -> {
			try {
				return response.getOutput().get().chunk();
			}
			catch (Exception exception) {
				throw new AssertionError(exception);
			}
		}).reduce("", String::concat);

		assertFalse(output.contains("internal reasoning"));
		assertTrue(output.contains("# Report"));
	}

	@Test
	void apply_preservesSplitEchartsBlocksExactly() throws Exception {
		OverAllState state = createTestState();
		setupBasicState(state);
		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.concat(Flux.just(ChatResponseUtil.createPureResponse("## 趋势\n\n```echarts\n{")),
					Flux.just(ChatResponseUtil.createPureResponse("\"series\":[{\"data\":[1,2]}]}")),
					Flux.just(ChatResponseUtil.createPureResponse("\n```\n"))));

		Map<String, Object> result = reportGeneratorNode.apply(state);
		@SuppressWarnings("unchecked")
		Flux<GraphResponse<StreamingOutput>> stream = (Flux<GraphResponse<StreamingOutput>>) result.get(RESULT);

		String output = collectVisibleOutput(stream);
		assertTrue(output.contains("```echarts\n{\"series\":[{\"data\":[1,2]}]}\n```"));
		assertEquals(output, collectVisibleOutput(stream));
	}

	@Test
	void apply_emptyExecutionResults_returnsGenerator() throws Exception {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("查询数据");
		String planJson = TestFixtures
			.planToJson(TestFixtures.createPlan("分析", TestFixtures.createReportStep(1, "生成报告")));

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 1,
				SQL_EXECUTE_NODE_OUTPUT, new HashMap<>(), AGENT_ID, "2"));

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(2L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean())).thenReturn(Flux.just(ChatResponseUtil.createPureResponse("暂无数据可分析")));

		Map<String, Object> result = reportGeneratorNode.apply(state);

		assertNotNull(result);
		assertTrue(result.containsKey(RESULT));
	}

	@Test
	void apply_withMultipleSteps_includesAllStepData() throws Exception {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("分析销售数据");
		String planJson = TestFixtures
			.planToJson(TestFixtures.createPlan("多步骤分析", TestFixtures.createSqlStep(1, "查询销售"),
					TestFixtures.createSqlStep(2, "查询客户"), TestFixtures.createReportStep(3, "综合分析报告")));

		HashMap<String, String> executionResults = new HashMap<>();
		executionResults.put("step_1", "[{\"total\":1000}]");
		executionResults.put("step_2", "[{\"customers\":50}]");

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 3,
				SQL_EXECUTE_NODE_OUTPUT, executionResults, AGENT_ID, "3"));

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(3L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("<h1>综合报告</h1>")));

		Map<String, Object> result = reportGeneratorNode.apply(state);

		assertNotNull(result);
		assertTrue(result.containsKey(RESULT));
	}

	@Test
	void apply_llmFailure_throwsException() {
		OverAllState state = createTestState();
		setupBasicState(state);

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(1L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean())).thenThrow(new RuntimeException("LLM unavailable"));

		assertThrows(RuntimeException.class, () -> reportGeneratorNode.apply(state));
	}

	@Test
	void apply_invalidAgentId_usesNullForConfigs() throws Exception {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("查询");
		String planJson = TestFixtures
			.planToJson(TestFixtures.createPlan("分析", TestFixtures.createReportStep(1, "报告")));

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 1,
				SQL_EXECUTE_NODE_OUTPUT, new HashMap<>(), AGENT_ID, "not-a-number"));

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), isNull()))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean())).thenReturn(Flux.just(ChatResponseUtil.createPureResponse("report")));

		Map<String, Object> result = reportGeneratorNode.apply(state);

		assertNotNull(result);
		assertTrue(result.containsKey(RESULT));
	}

	@Test
	void apply_missingPlanOutput_throwsException() {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("查询");
		state.updateState(
				Map.of(QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 1, SQL_EXECUTE_NODE_OUTPUT, new HashMap<>()));

		assertThrows(IllegalStateException.class, () -> reportGeneratorNode.apply(state));
	}

	@Test
	void apply_stepIndexOutOfRange_throwsException() {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("查询");
		String planJson = TestFixtures.planToJson(TestFixtures.createPlan("分析", TestFixtures.createSqlStep(1, "查询")));

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 5,
				SQL_EXECUTE_NODE_OUTPUT, new HashMap<>(), AGENT_ID, "1"));

		assertThrows(IllegalStateException.class, () -> reportGeneratorNode.apply(state));
	}

	@Test
	void apply_withAnalysisResults_includesAnalysis() throws Exception {
		OverAllState state = createTestState();
		QueryEnhanceOutputDTO dto = TestFixtures.createQueryEnhanceDTO("分析");
		String planJson = TestFixtures.planToJson(TestFixtures.createPlan("分析", TestFixtures.createSqlStep(1, "查询"),
				TestFixtures.createReportStep(2, "报告")));

		HashMap<String, String> executionResults = new HashMap<>();
		executionResults.put("step_1", "[{\"count\":100}]");
		executionResults.put("step_1_analysis", "数据趋势上升");

		state.updateState(Map.of(PLANNER_NODE_OUTPUT, planJson, QUERY_ENHANCE_NODE_OUTPUT, dto, PLAN_CURRENT_STEP, 2,
				SQL_EXECUTE_NODE_OUTPUT, executionResults, AGENT_ID, "4"));

		when(promptConfigService.getOptimizationConfigs(eq("report-generator"), eq(4L)))
			.thenReturn(Collections.emptyList());
		when(llmService.callWithState(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyBoolean()))
			.thenReturn(Flux.just(ChatResponseUtil.createPureResponse("<p>分析完成</p>")));

		Map<String, Object> result = reportGeneratorNode.apply(state);

		assertNotNull(result);
		assertTrue(result.containsKey(RESULT));
	}

	@Test
	void buildLineageMarkdown_rendersFileVersionAndEscapesCells() {
		String markdown = reportGeneratorNode.buildLineageMarkdown(List.of(Map.of("source_file_name", "quality|1.xlsx",
				"source_file_sha256", "1234567890abcdef", "source_sheet", "Sheet1", "source_imported_at",
				"2026-07-16 10:00:00")));

		assertTrue(markdown.contains("## 数据来源"));
		assertTrue(markdown.contains("quality\\|1.xlsx"));
		assertTrue(markdown.contains("1234567890ab"));
		assertFalse(markdown.contains("1234567890abcdef"));
	}

	@Test
	void buildReportSuffix_emptyReportReturnsFailureMessage() {
		String suffix = reportGeneratorNode.buildReportSuffix("", "");

		assertEquals("报告生成失败：模型未返回报告内容，请重试。", suffix);
	}

	@Test
	void buildReportSuffix_closesUnterminatedChartBeforeLineage() {
		String suffix = reportGeneratorNode.buildReportSuffix("### 趋势图\n```echarts\n{\"series\": [",
				"\n\n## 数据来源\n\nsource.xlsx");

		assertTrue(suffix.startsWith("\n```\n"));
		assertTrue(suffix.contains("## 数据来源"));
	}

	@Test
	void buildReportSuffix_doesNotAddFenceWhenChartIsClosed() {
		String suffix = reportGeneratorNode.buildReportSuffix("```echarts\n{}\n```", "\n\n## 数据来源");

		assertEquals("\n\n## 数据来源", suffix);
	}

	@Test
	private String collectVisibleOutput(Flux<GraphResponse<StreamingOutput>> stream) {
		return stream.collectList().block().stream().filter(response -> !response.isDone()).map(response -> {
			try {
				return response.getOutput().get().chunk();
			}
			catch (Exception exception) {
				throw new AssertionError(exception);
			}
		}).reduce("", String::concat);
	}

}
