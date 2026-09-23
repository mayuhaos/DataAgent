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

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.entity.ConversationTopic;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Uses structured output and deliberately falls back to a safe single-turn plan. */
@Service
@RequiredArgsConstructor
public class LlmConversationPlanClient implements ConversationPlanClient {

	private final LlmService llmService;

	@Override
	public OperationPlan plan(String userMessage, String sessionSummary, List<ConversationTopic> topics,
			List<AnalysisArtifact> artifacts) {
		try {
			String context = JsonUtil.getObjectMapper()
				.writeValueAsString(Map.of("sessionSummary", truncate(sessionSummary, 4000), "topics", safeTopicCards(topics),
						"candidateArtifacts", safeArtifactCards(artifacts)));
			String prompt = "Return only OperationPlan JSON matching the schema. Never return SQL or tool calls.\n"
					+ "userMessage=" + userMessage + "\ncontext=" + context;
			String json = llmService.blockToString(llmService.callUser(prompt, OperationPlan.class));
			return JsonUtil.getObjectMapper().readValue(json, OperationPlan.class);
		}
		catch (Exception ignored) {
			OperationPlan fallback = new OperationPlan();
			fallback.setOperation(OperationPlan.Operation.CREATE);
			fallback.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
			fallback.setConfidence(0D);
			fallback.setReason("conversation planner unavailable; delegated to normal query flow");
			return fallback;
		}
	}

	private List<Map<String, Object>> safeArtifactCards(List<AnalysisArtifact> artifacts) {
		List<Map<String, Object>> cards = new ArrayList<>();
		for (AnalysisArtifact artifact : artifacts.stream().limit(10).toList()) {
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("id", artifact.getId());
			card.put("topicId", artifact.getTopicId());
			card.put("type", artifact.getType());
			card.put("inputSpec", truncate(artifact.getInputSpec(), 1500));
			card.put("schema", truncate(artifact.getResultSchema(), 1500));
			card.put("summary", truncate(artifact.getResultSummary(), 1500));
			card.put("presentationSpec", truncate(artifact.getPresentationSpec(), 1000));
			cards.add(card);
		}
		return cards;
	}

	private List<Map<String, String>> safeTopicCards(List<ConversationTopic> topics) {
		return topics.stream().limit(10).map(topic -> Map.of("id", topic.getId(),
				"title", truncate(topic.getTitle(), 300), "summary", truncate(topic.getSummary(), 1200))).toList();
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

}
