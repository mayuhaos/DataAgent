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

import com.alibaba.cloud.ai.dataagent.dto.conversation.ConversationMessageRequest;
import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.entity.ChatSession;
import com.alibaba.cloud.ai.dataagent.entity.ConversationTopic;
import com.alibaba.cloud.ai.dataagent.entity.ConversationAudit;
import com.alibaba.cloud.ai.dataagent.service.chat.AnalysisArtifactService;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatSessionService;
import com.alibaba.cloud.ai.dataagent.vo.ConversationMessageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conversation orchestration only; SQL execution remains in the existing graph. */
@Service
@RequiredArgsConstructor
public class ConversationService {

	private final ChatSessionService chatSessionService;
	private final ChatMessageService chatMessageService;
	private final AnalysisArtifactService analysisArtifactService;
	private final ConversationTopicService conversationTopicService;
	private final ConversationPlanClient conversationPlanClient;
	private final PlanValidator planValidator;
	private final LocalResultTransformer localResultTransformer;
	private final ConversationAuditService conversationAuditService;
	private final ReportRevisionService reportRevisionService;

	@Transactional
	public ConversationMessageResponse submit(String sessionId, ConversationMessageRequest request) {
		ChatSession session = chatSessionService.findBySessionId(sessionId);
		if (session == null) {
			throw new IllegalArgumentException("session does not exist");
		}
		List<ConversationTopic> topics = conversationTopicService.findBySessionId(sessionId);
		List<AnalysisArtifact> artifacts = analysisArtifactService.findBySessionId(sessionId);
		OperationPlan plan = planValidator.validate(conversationPlanClient.plan(request.getUserMessage(),
				buildSessionSummary(topics), topics, artifacts), topics, artifacts);
		String topicId = plan.getTarget().getTopicIds().isEmpty() ? null : plan.getTarget().getTopicIds().get(0);
		if (topicId == null && (topics.isEmpty() || plan.getOperation() == OperationPlan.Operation.CREATE
				|| plan.getOperation() == OperationPlan.Operation.SWITCH_TOPIC)) {
			ConversationTopic topic = conversationTopicService.create(ConversationTopic.builder().sessionId(sessionId)
					.title(shorten(request.getUserMessage(), 80)).summary(shorten(request.getUserMessage(), 300))
					.status("active").build());
			topicId = topic.getId();
		}
		String threadId = UUID.randomUUID().toString();
		chatMessageService.saveMessage(ChatMessage.builder().sessionId(sessionId).role("user")
				.content(request.getUserMessage()).messageType("text").build());
		chatSessionService.updateSessionTime(sessionId);
		String status = plan.getExecutionMode() == OperationPlan.ExecutionMode.ASK_CLARIFICATION ? "CLARIFICATION"
				: plan.getExecutionMode() == OperationPlan.ExecutionMode.REQUERY ? "DISPATCH_TO_GRAPH" : "LOCAL_EXECUTION";
		AnalysisArtifact derivedArtifact = null;
		if ("LOCAL_EXECUTION".equals(status)) {
			derivedArtifact = persistDerivedArtifact(sessionId, plan, artifacts, request.getUserMessage());
		}
		String message = plan.getExecutionMode() == OperationPlan.ExecutionMode.ASK_CLARIFICATION
				? plan.getClarificationQuestion() : plan.getReason();
		if (plan.getExecutionMode() == OperationPlan.ExecutionMode.ASK_CLARIFICATION) {
			chatMessageService.saveMessage(ChatMessage.builder().sessionId(sessionId).role("assistant").content(message)
					.messageType("clarification").metadata(serialize(plan)).build());
		}
		conversationAuditService.save(ConversationAudit.builder().sessionId(sessionId).threadId(threadId)
				.modelContextHash(conversationAuditService.hash(buildSessionSummary(topics))).operationPlan(serialize(plan))
				.validationResult(status).executionMode(plan.getExecutionMode().name())
				.referencedArtifactIds(serialize(plan.getTarget().getArtifactIds())).build());
		return ConversationMessageResponse.builder().sessionId(sessionId).agentId(String.valueOf(session.getAgentId()))
				.topicId(topicId)
				.threadId(threadId).plan(plan).status(status)
				.message(message).resultArtifactId(derivedArtifact == null ? null : derivedArtifact.getId()).build();
	}

	private AnalysisArtifact persistDerivedArtifact(String sessionId, OperationPlan plan, List<AnalysisArtifact> artifacts,
			String userMessage) {
		if (plan.getTarget().getArtifactIds().size() != 1) {
			return null;
		}
		AnalysisArtifact source = artifacts.stream()
			.filter(artifact -> plan.getTarget().getArtifactIds().contains(artifact.getId()))
			.findFirst()
			.orElse(null);
		if (source == null) {
			return null;
		}
		AnalysisArtifact artifact = AnalysisArtifact.builder()
			.sessionId(sessionId)
			.topicId(source.getTopicId())
			.parentArtifactId(source.getId())
			.type(plan.getExecutionMode() == OperationPlan.ExecutionMode.REGENERATE_REPORT ? "REPORT" : source.getType())
			.inputSpec(source.getInputSpec())
			.userQuestion(userMessage)
			.sqlQuery(source.getSqlQuery())
			.resultRef(source.getResultRef())
			.contentRef(source.getContentRef())
			.resultSchema(source.getResultSchema())
			.resultSample(source.getResultSample())
			.resultSummary(source.getResultSummary())
			.presentationSpec(serialize(plan.getChanges().getPresentation()))
			.provenance(source.getProvenance())
			.expireTime(source.getExpireTime())
			.status("SUCCESS")
			.build();
		if (plan.getExecutionMode() == OperationPlan.ExecutionMode.TRANSFORM_LOCAL) {
			artifact.setResultRef(localResultTransformer.transform(source, plan.getChanges().getData()));
		}
		if (plan.getExecutionMode() == OperationPlan.ExecutionMode.REGENERATE_REPORT) {
			artifact.setContentRef(reportRevisionService.revise(source, plan.getChanges().getReport()));
		}
		return analysisArtifactService.save(artifact);
	}

	private String buildSessionSummary(List<ConversationTopic> topics) {
		return topics.stream().limit(5).map(topic -> topic.getTitle() + ": " + topic.getSummary())
				.reduce("", (left, right) -> left + "\n" + right);
	}

	private String shorten(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, maxLength);
	}

	private String serialize(Object value) {
		try {
			return com.alibaba.cloud.ai.dataagent.util.JsonUtil.getObjectMapper().writeValueAsString(value);
		}
		catch (Exception ex) {
			return "{}";
		}
	}

}
