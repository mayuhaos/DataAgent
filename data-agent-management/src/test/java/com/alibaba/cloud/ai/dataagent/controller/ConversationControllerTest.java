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
package com.alibaba.cloud.ai.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.dto.conversation.ConversationMessageRequest;
import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.service.chat.AnalysisArtifactService;
import com.alibaba.cloud.ai.dataagent.service.conversation.AnalysisResultStore;
import com.alibaba.cloud.ai.dataagent.service.conversation.ConversationService;
import com.alibaba.cloud.ai.dataagent.service.conversation.ConversationTopicService;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.vo.ConversationMessageResponse;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

	@Mock private ConversationService conversationService;
	@Mock private ConversationTopicService conversationTopicService;
	@Mock private AnalysisArtifactService analysisArtifactService;
	@Mock private GraphService graphService;
	@Mock private AnalysisResultStore analysisResultStore;
	@Mock private ServerHttpResponse response;
	private ConversationController controller;

	@BeforeEach
	void setUp() {
		controller = new ConversationController(conversationService, conversationTopicService, analysisArtifactService,
				graphService, analysisResultStore);
		when(response.getHeaders()).thenReturn(new HttpHeaders());
	}

	@Test
	void requeryPlanIsDispatchedToExistingGraphWithFreshThread() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
		when(conversationService.submit(any(), any())).thenReturn(ConversationMessageResponse.builder()
				.sessionId("s1").agentId("7").topicId("topic-1").threadId("thread-1").plan(plan)
				.status("DISPATCH_TO_GRAPH").build());
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			reactor.core.publisher.Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = invocation.getArgument(0);
			sink.tryEmitNext(ServerSentEvent.builder(GraphNodeResponse.complete("7", "thread-1")).event("complete").build());
			sink.tryEmitComplete();
			return null;
		}).when(graphService).graphStreamProcess(any(), any());
		ConversationMessageRequest request = new ConversationMessageRequest();
		request.setUserMessage("华南呢");

		Flux<ServerSentEvent<GraphNodeResponse>> stream = controller.submit("s1", request, response);

		StepVerifier.create(stream).expectNextMatches(event -> "plan".equals(event.event()))
			.expectNextMatches(event -> "complete".equals(event.event())).verifyComplete();
		ArgumentCaptor<GraphRequest> requestCaptor = ArgumentCaptor.forClass(GraphRequest.class);
		verify(graphService).graphStreamProcess(any(), requestCaptor.capture());
		assertEquals("s1", requestCaptor.getValue().getConversationId());
		assertEquals("thread-1", requestCaptor.getValue().getThreadId());
		assertEquals("topic-1", requestCaptor.getValue().getTopicId());
	}

}
