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
package com.alibaba.cloud.ai.dataagent.service.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.alibaba.cloud.ai.dataagent.dto.conversation.ConversationMessageRequest;
import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.entity.ChatSession;
import com.alibaba.cloud.ai.dataagent.service.chat.AnalysisArtifactService;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatSessionService;
import com.alibaba.cloud.ai.dataagent.vo.ConversationMessageResponse;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

	@Mock private ChatSessionService chatSessionService;
	@Mock private ChatMessageService chatMessageService;
	@Mock private AnalysisArtifactService analysisArtifactService;
	@Mock private ConversationTopicService conversationTopicService;
	@Mock private ConversationPlanClient conversationPlanClient;
	@Mock private ArtifactReferenceResolver artifactReferenceResolver;
	@Mock private LocalResultTransformer localResultTransformer;
	@Mock private PresentationSpecEditor presentationSpecEditor;
	@Mock private ConversationAuditService conversationAuditService;
	@Mock private ReportRevisionService reportRevisionService;
	private ConversationService service;

	@BeforeEach
	void setUp() {
		service = new ConversationService(chatSessionService, chatMessageService, analysisArtifactService,
				conversationTopicService, conversationPlanClient, artifactReferenceResolver, new PlanValidator(), localResultTransformer,
				presentationSpecEditor, conversationAuditService, reportRevisionService);
		when(conversationAuditService.hash(any())).thenReturn("context-hash");
		when(chatSessionService.findBySessionId("session-1"))
				.thenReturn(ChatSession.builder().id("session-1").agentId(42).build());
		when(conversationTopicService.findBySessionId("session-1")).thenReturn(List.of());
		org.mockito.Mockito.lenient().when(conversationTopicService.create(any())).thenAnswer(invocation -> {
			com.alibaba.cloud.ai.dataagent.entity.ConversationTopic topic = invocation.getArgument(0);
			topic.setId("topic-1");
			return topic;
		});
		when(analysisArtifactService.findBySessionId("session-1")).thenReturn(List.of());
		org.mockito.Mockito.lenient().when(artifactReferenceResolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
				.thenReturn(ArtifactReferenceResolver.Resolution.none());
	}

	@Test
	void chartOnlyRequestReusesLatestResultWithoutCallingPlanner() {
		var source = com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact.builder()
				.id("chart-1").sessionId("session-1").type("CHART").sqlQuery("original SQL")
				.resultRef("original-data").presentationSpec("{}").build();
		when(analysisArtifactService.findBySessionId("session-1")).thenReturn(List.of(source));
		when(artifactReferenceResolver.cards(any())).thenReturn(List.of(
				new ArtifactReferenceResolver.Card("chart-1", "图表01", "点位")));
		when(presentationSpecEditor.apply(any(), any())).thenReturn("{\"type\":\"bar\",\"series\":[]}");
		when(reportRevisionService.renderChart(any(), any())).thenReturn("updated-report");
		when(reportRevisionService.getContent("session-1", "updated-report"))
				.thenReturn("```echarts\n{\"series\":[{\"type\":\"bar\",\"data\":[15,1]}]}\n```");
		when(analysisArtifactService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("上面这个图用柱状图展示");
		var result = service.submit("session-1", request);
		assertEquals("LOCAL_EXECUTION", result.getStatus());
		assertEquals(OperationPlan.ExecutionMode.REUSE, result.getPlan().getExecutionMode());
		org.mockito.Mockito.verifyNoInteractions(conversationPlanClient);
		verify(analysisArtifactService).save(org.mockito.ArgumentMatchers.argThat(a ->
				"original-data".equals(a.getResultRef()) && "original SQL".equals(a.getSqlQuery())
				&& "updated-report".equals(a.getContentRef())));
		verify(chatMessageService).saveMessage(org.mockito.ArgumentMatchers.argThat(message ->
				"markdown-report".equals(message.getMessageType()) && message.getContent().contains("\"bar\"")));
	}

	@Test
	void defectQuantityCannotBeEquatedToProductOrRecordCount() {
		var request = new ConversationMessageRequest();
		request.setUserMessage("一厂2026年6月设备环境缺陷合计4456，所以不良产品就是4456件，对吗？");
		var response = service.submit("session-1", request);
		assertEquals("CLARIFICATION", response.getStatus());
		org.junit.jupiter.api.Assertions.assertTrue(response.getMessage().contains("SUM(defect_count)"));
		org.junit.jupiter.api.Assertions.assertTrue(response.getMessage().contains("COUNT(*)"));
		org.mockito.Mockito.verifyNoInteractions(conversationPlanClient);
	}

	@Test
	void returnToBarParsesAxisBoundWithoutPlanner() {
		var source = com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact.builder()
				.id("chart-1").sessionId("session-1").type("CHART").presentationSpec("{}").build();
		when(analysisArtifactService.findBySessionId("session-1")).thenReturn(List.of(source));
		when(artifactReferenceResolver.cards(any())).thenReturn(List.of(new ArtifactReferenceResolver.Card("chart-1", "图表01", "数量")));
		when(presentationSpecEditor.apply(any(), any())).thenReturn("{\"series\":[]}");
		when(reportRevisionService.renderChart(any(), any())).thenReturn("updated-report");
		when(analysisArtifactService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		var request = new ConversationMessageRequest();
		request.setUserMessage("再改回柱状图，Y轴上限设为5000。");
		var response = service.submit("session-1", request);
		assertEquals("LOCAL_EXECUTION", response.getStatus());
		assertEquals("bar", response.getPlan().getChanges().getPresentation().getChartType());
		assertEquals(5000D, response.getPlan().getChanges().getPresentation().getYAxisMax());
		org.mockito.Mockito.verifyNoInteractions(conversationPlanClient);
	}

	@Test
	void aiPlannedChartEditPersistsTheRenderedReportForHistoryReload() {
		String question = "帮我把下面这个饼状图换成柱状图。";
		var source = com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact.builder()
				.id("pie-1").sessionId("session-1").type("CHART")
				.presentationSpec("{\"series\":[{\"type\":\"pie\"}]}").build();
		when(analysisArtifactService.findBySessionId("session-1")).thenReturn(List.of(source));
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
		plan.getTarget().setArtifactIds(List.of("pie-1"));
		plan.getChanges().getPresentation().setChartType("bar");
		when(conversationPlanClient.plan(eq(question), any(), any(), any())).thenReturn(plan);
		when(presentationSpecEditor.apply(any(), any())).thenReturn("{\"series\":[{\"type\":\"bar\"}]}");
		when(reportRevisionService.renderChart(any(), any())).thenReturn("new-content");
		String report = "```echarts\n{\"series\":[{\"type\":\"bar\",\"data\":[81.72,18.07,0.21]}]}\n```";
		when(reportRevisionService.getContent("session-1", "new-content")).thenReturn(report);
		when(analysisArtifactService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage(question);
		assertEquals("LOCAL_EXECUTION", service.submit("session-1", request).getStatus());
		verify(chatMessageService).saveMessage(org.mockito.ArgumentMatchers.argThat(message ->
				"markdown-report".equals(message.getMessageType()) && report.equals(message.getContent())));
	}

	@Test
	void usesNewThreadForEveryOrdinaryMessageAndDispatchesRequery() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
		plan.setReason("data scope changed");
		when(conversationPlanClient.plan(eq("华南呢"), any(), any(), any())).thenReturn(plan);
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("华南呢");

		ConversationMessageResponse first = service.submit("session-1", request);
		ConversationMessageResponse second = service.submit("session-1", request);

		assertEquals("DISPATCH_TO_GRAPH", first.getStatus());
		assertNotEquals(first.getThreadId(), second.getThreadId());
	}

	@Test
	void requeryDoesNotPersistPlannerReasonAsAssistantAnswer() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
		plan.setReason("当前会话中没有可直接编辑的图表，请先生成图表。");
		when(conversationPlanClient.plan(eq("改为折线图"), any(), any(), any())).thenReturn(plan);
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("改为折线图");

		ConversationMessageResponse response = service.submit("session-1", request);

		assertEquals("DISPATCH_TO_GRAPH", response.getStatus());
		verify(chatMessageService, times(1)).saveMessage(org.mockito.ArgumentMatchers.argThat(message ->
				"user".equals(message.getRole()) && "改为折线图".equals(message.getContent())));
	}

	@Test
	void clarificationIsPersistedAsAClarificationMessage() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.ASK_CLARIFICATION);
		plan.setClarificationQuestion("请说明要修改哪一张图表。");
		when(conversationPlanClient.plan(eq("改一下"), any(), any(), any())).thenReturn(plan);
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("改一下");

		ConversationMessageResponse response = service.submit("session-1", request);

		assertEquals("CLARIFICATION", response.getStatus());
		verify(chatMessageService, times(2)).saveMessage(any(ChatMessage.class));
		verify(chatMessageService).saveMessage(org.mockito.ArgumentMatchers.argThat(message ->
				"assistant".equals(message.getRole()) && "clarification".equals(message.getMessageType())
						&& "请说明要修改哪一张图表。".equals(message.getContent())));
	}

	@Test
	void revisionsCreateAChildArtifactWithoutQuerying() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REGENERATE_REPORT);
		plan.getTarget().setArtifactIds(List.of("report-1"));
		plan.setReason("rewrite existing report");
		com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact source = com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact
			.builder().id("report-1").sessionId("session-1").type("REPORT").userQuestion("old").status("SUCCESS")
			.build();
		when(analysisArtifactService.findBySessionId("session-1")).thenReturn(List.of(source));
		when(presentationSpecEditor.apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn("{}");
		when(conversationPlanClient.plan(eq("精简报告"), any(), any(), any())).thenReturn(plan);
		when(analysisArtifactService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(reportRevisionService.revise(any(), any())).thenReturn("revised-report");
		when(reportRevisionService.getContent("session-1", "revised-report")).thenReturn("精简后的报告");
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("精简报告");

		ConversationMessageResponse response = service.submit("session-1", request);

		assertEquals("LOCAL_EXECUTION", response.getStatus());
		verify(analysisArtifactService).save(org.mockito.ArgumentMatchers.argThat(artifact ->
				"report-1".equals(artifact.getParentArtifactId()) && "REPORT".equals(artifact.getType())));
	}

	@Test
	void assignsDistinctThreadsAcrossFiftyOrdinaryTurns() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
		when(conversationPlanClient.plan(any(), any(), any(), any())).thenReturn(plan);
		Set<String> threadIds = new HashSet<>();
		for (int index = 0; index < 50; index++) {
			ConversationMessageRequest request = new ConversationMessageRequest();
			request.setUserMessage("question-" + index);
			threadIds.add(service.submit("session-1", request).getThreadId());
		}

		assertEquals(50, threadIds.size());
	}

	@Test
	void firstAnalysisWithThreeChartsDoesNotEnterChartEditShortcut() {
		String question = "查询一厂2026年6月喷涂完整缺陷明细，生成三张图：第1张柱状图，第2张三类自身占比饼图，第3张Top3柱状图。";
		OperationPlan plan = new OperationPlan();
		plan.setOperation(OperationPlan.Operation.CREATE);
		plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
		when(conversationPlanClient.plan(eq(question), any(), any(), any())).thenReturn(plan);
		org.mockito.Mockito.lenient().when(artifactReferenceResolver.resolve(any(), any())).thenReturn(ArtifactReferenceResolver.Resolution.none());
		var request = new ConversationMessageRequest(); request.setUserMessage(question);
		assertEquals("DISPATCH_TO_GRAPH", service.submit("session-1", request).getStatus());
		verify(conversationPlanClient).plan(eq(question), any(), any(), any());
	}

	@Test
	void missingArtifactCannotBeReportedAsAnUpdatedChart() {
		var request = new ConversationMessageRequest(); request.setUserMessage("Y轴上限5000");
		assertEquals("CLARIFICATION", service.submit("session-1", request).getStatus());
		org.mockito.Mockito.verify(analysisArtifactService, org.mockito.Mockito.never()).save(any());
	}

}
