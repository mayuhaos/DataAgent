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

import com.alibaba.cloud.ai.dataagent.service.graph.Context.ClarificationContextManager;
import com.alibaba.cloud.ai.dataagent.service.chat.AnalysisArtifactService;
import com.alibaba.cloud.ai.dataagent.service.conversation.ArtifactReferenceResolver;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.workflow.IncompleteResultEditDetector;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AWAITING_CLARIFICATION;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CLARIFICATION_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CLARIFICATION_QUESTION;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ORIGINAL_USER_QUERY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFINED_USER_QUERY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_CONVERSATION_ID;

/** Sends a clarification before any retrieval, query enhancement, or SQL work begins. */
@Component
@RequiredArgsConstructor
public class EarlyClarificationNode implements NodeAction {

	private final ClarificationContextManager clarificationContextManager;

	private final AnalysisArtifactService analysisArtifactService;

	private final ArtifactReferenceResolver artifactReferenceResolver;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String originalQuery = StateUtil.getStringValue(state, ORIGINAL_USER_QUERY,
				StateUtil.getStringValue(state, INPUT_KEY));
		String inputQuery = StateUtil.getStringValue(state, INPUT_KEY);
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String conversationId = StateUtil.getStringValue(state, TRACE_CONVERSATION_ID, "");
		int clarificationCount = StateUtil.getObjectValue(state, CLARIFICATION_COUNT, Integer.class, 0);
		String question = clarificationQuestion(inputQuery, conversationId);

		clarificationContextManager.startClarification(threadId, originalQuery, question, clarificationCount + 1);
		Map<String, Object> result = Map.of(
				ORIGINAL_USER_QUERY, originalQuery,
				REFINED_USER_QUERY, inputQuery,
				CLARIFICATION_QUESTION, question,
				CLARIFICATION_COUNT, clarificationCount + 1,
				AWAITING_CLARIFICATION, true);
		Flux<ChatResponse> responseFlux = Flux.just(ChatResponseUtil.createResponse(question));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, ignored -> result, responseFlux);
		return Map.of(CLARIFICATION_QUESTION, generator);
	}

	private String clarificationQuestion(String inputQuery, String conversationId) {
		if (conversationId.isBlank()) {
			return IncompleteResultEditDetector.clarificationQuestion();
		}
		ArtifactReferenceResolver.Resolution reference = artifactReferenceResolver.resolve(inputQuery,
				analysisArtifactService.findBySessionId(conversationId));
		if (reference.status() != ArtifactReferenceResolver.Resolution.Status.AMBIGUOUS) {
			return IncompleteResultEditDetector.clarificationQuestion();
		}
		String labels = reference.candidates().stream().map(ArtifactReferenceResolver.Card::label)
				.collect(java.util.stream.Collectors.joining("；"));
		return "无法确认要修改哪一张图表，请指定图表序号或名称：" + labels + "。";
	}

}
