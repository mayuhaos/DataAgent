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
	private final ArtifactReferenceResolver artifactReferenceResolver;
	private final PlanValidator planValidator;
	private final LocalResultTransformer localResultTransformer;
	private final PresentationSpecEditor presentationSpecEditor;
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
		java.util.Map<String, String> scopeContext = request.getContext() == null ? java.util.Map.of() : request.getContext();
		ArtifactReferenceResolver.Resolution reference = artifactReferenceResolver.resolve(request.getUserMessage(), artifacts);
		if (reference.status() == ArtifactReferenceResolver.Resolution.Status.NOT_FOUND) {
			return clarification(session, sessionId, request.getUserMessage(), "未找到指定图表。可引用的图表：" + labels(reference.candidates()));
		}
		if (reference.status() == ArtifactReferenceResolver.Resolution.Status.AMBIGUOUS) {
			return clarification(session, sessionId, request.getUserMessage(), "无法确认要修改哪一张图表：" + labels(reference.candidates()));
		}
		java.util.Map<String, String> plannerContext = new java.util.LinkedHashMap<>(scopeContext);
		reference.resolvedArtifact().ifPresent(artifact -> {
			plannerContext.put("resolvedArtifactId", artifact.getId());
			plannerContext.put("resolvedArtifactLabel", reference.label());
			plannerContext.put("referenceResolution", reference.reason());
		});
		OperationPlan planned = chartOnlyPlan(request.getUserMessage(), artifacts, reference);
		if (planned == null) planned = plannerContext.isEmpty()
				? conversationPlanClient.plan(request.getUserMessage(), buildSessionSummary(topics), topics, artifacts)
				: conversationPlanClient.plan(request.getUserMessage(), buildSessionSummary(topics), topics, artifacts, plannerContext);
		if (reference.status() == ArtifactReferenceResolver.Resolution.Status.RESOLVED) {
			planned.getTarget().setArtifactIds(java.util.List.of(reference.artifact().getId()));
		}
		OperationPlan plan = planValidator.validate(planned, topics, artifacts);
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
			try {
				derivedArtifact = persistDerivedArtifact(sessionId, plan, artifacts, request.getUserMessage());
				if (derivedArtifact != null && derivedArtifact.getContentRef() != null) {
					String content = reportRevisionService.getContent(sessionId, derivedArtifact.getContentRef());
					if (content != null && !content.isBlank()) {
						chatMessageService.saveMessage(ChatMessage.builder().sessionId(sessionId).role("assistant")
								.messageType("markdown-report").content(content).build());
					}
				}
			}
			catch (IllegalArgumentException | IllegalStateException ex) {
				plan.setExecutionMode(OperationPlan.ExecutionMode.ASK_CLARIFICATION);
				plan.setClarificationQuestion(ex instanceof PresentationSpecEditor.PieConversionException
						? ex.getMessage()
						: "原图无法直接转换为所需图形，请确认图表的分类和数值；未重新查询数据。");
				status = "CLARIFICATION";
			}
		}
		String message = plan.getExecutionMode() == OperationPlan.ExecutionMode.ASK_CLARIFICATION
				? plan.getClarificationQuestion() : localExecutionMessage(plan, artifacts, derivedArtifact);
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

	private ConversationMessageResponse clarification(ChatSession session, String sessionId, String userMessage, String question) {
		String threadId = UUID.randomUUID().toString();
		OperationPlan plan = new OperationPlan();
		plan.setOperation(OperationPlan.Operation.ASK_CLARIFICATION);
		plan.setExecutionMode(OperationPlan.ExecutionMode.ASK_CLARIFICATION);
		plan.setClarificationQuestion(question);
		plan.setReason(question);
		chatMessageService.saveMessage(ChatMessage.builder().sessionId(sessionId).role("user")
			.content(userMessage).messageType("text").build());
		chatMessageService.saveMessage(ChatMessage.builder().sessionId(sessionId).role("assistant").content(question)
			.messageType("clarification").metadata(serialize(plan)).build());
		chatSessionService.updateSessionTime(sessionId);
		conversationAuditService.save(ConversationAudit.builder().sessionId(sessionId).threadId(threadId)
			.modelContextHash(conversationAuditService.hash("artifact-reference"))
			.operationPlan(serialize(plan)).validationResult("CLARIFICATION")
			.executionMode(plan.getExecutionMode().name()).referencedArtifactIds("[]").build());
		return ConversationMessageResponse.builder().sessionId(sessionId).agentId(String.valueOf(session.getAgentId()))
			.threadId(threadId).plan(plan).status("CLARIFICATION").message(question).build();
	}

	private String labels(List<ArtifactReferenceResolver.Card> candidates) {
		return candidates.stream().map(ArtifactReferenceResolver.Card::label).collect(java.util.stream.Collectors.joining("；"));
	}

	private String localExecutionMessage(OperationPlan plan, List<AnalysisArtifact> artifacts, AnalysisArtifact derivedArtifact) {
		if (plan.getExecutionMode() == OperationPlan.ExecutionMode.REQUERY) {
			return plan.getReason();
		}
		if (plan.getReason() != null && !plan.getReason().isBlank()) {
			return plan.getReason();
		}
		if (derivedArtifact != null && plan.getTarget().getArtifactIds().size() == 1) {
			String sourceId = plan.getTarget().getArtifactIds().get(0);
			return "已更新图表 " + sourceId + "，并创建新版本 " + derivedArtifact.getId() + "。";
		}
		return "已处理该结果修改。";
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
			.presentationSpec(presentationSpecEditor.apply(chartSpec(source), plan.getChanges().getPresentation()))
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
		else if (plan.getExecutionMode() == OperationPlan.ExecutionMode.REUSE
				&& artifact.getPresentationSpec().contains("\"series\"")) {
			artifact.setContentRef(reportRevisionService.renderChart(source, artifact.getPresentationSpec()));
		}
		return analysisArtifactService.save(artifact);
	}

	private String chartSpec(AnalysisArtifact source) {
		if (source.getPresentationSpec() != null && source.getPresentationSpec().contains("\"series\"")) {
			return source.getPresentationSpec();
		}
		if (!"QUERY_RESULT".equals(source.getType())) return source.getPresentationSpec();
		boolean matchingTurn = false;
		String spec = source.getPresentationSpec();
		for (ChatMessage message : chatMessageService.findBySessionId(source.getSessionId())) {
			if ("user".equals(message.getRole())) {
				matchingTurn = java.util.Objects.equals(message.getContent(), source.getUserQuestion());
			}
			else if (matchingTurn && "markdown-report".equals(message.getMessageType())) {
				var matcher = java.util.regex.Pattern.compile("```echarts?\\s*(\\{.*?\\})\\s*```",
						java.util.regex.Pattern.DOTALL).matcher(message.getContent());
				if (matcher.find()) {
					spec = matcher.group(1);
					if (matcher.find()) throw new IllegalArgumentException("multiple charts in legacy report");
				}
			}
		}
		return spec;
	}

	private OperationPlan chartOnlyPlan(String message, List<AnalysisArtifact> artifacts,
			ArtifactReferenceResolver.Resolution reference) {
		java.util.regex.Matcher match = java.util.regex.Pattern.compile(
				"^(?:(?:把|将)?(?:第\\s*[一二三四五六七八九十0-9]+\\s*(?:张|个)?\\s*图|图表\\s*\\d+|上面这个图|上面的图|这个图|这张图|上面这张图|上一个图|饼状图|柱状图)\\s*)?"
				+ "(?:换成|改成|改为|调整为|用)\\s*(饼状图|饼图|柱状图|柱形图)(?:展示|显示|回答)?[。！!\\s]*$")
				.matcher(message.trim());
		boolean explicitPartialPie = isExplicitPartialPieRequest(message);
		if (!match.matches() && !explicitPartialPie) return null;
		OperationPlan plan = new OperationPlan();
		java.util.List<ArtifactReferenceResolver.Card> cards = artifactReferenceResolver.cards(artifacts);
		if (cards.isEmpty()) {
			plan.setClarificationQuestion("没有可复用的历史图表，请先生成报表。");
			return plan;
		}
		AnalysisArtifact latest = reference.status() == ArtifactReferenceResolver.Resolution.Status.RESOLVED
				? reference.artifact()
				: artifacts.stream().filter(a -> cards.stream().anyMatch(c -> c.artifactId().equals(a.getId())))
				.max(java.util.Comparator.comparing(AnalysisArtifact::getCreateTime,
						java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
						.thenComparing(AnalysisArtifact::getId)).orElseThrow();
		if (reference.status() != ArtifactReferenceResolver.Resolution.Status.RESOLVED) {
			long count = artifacts.stream().filter(a -> cards.stream().anyMatch(c -> c.artifactId().equals(a.getId()))
					&& java.util.Objects.equals(ArtifactReferenceResolver.chartGroup(a), ArtifactReferenceResolver.chartGroup(latest))).count();
			if (count > 1) {
				plan.setClarificationQuestion("最近报表有多张图，请指定图表编号或名称。");
				return plan;
			}
		}
		plan.setOperation(OperationPlan.Operation.REVISE);
		plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
		plan.getTarget().setArtifactIds(java.util.List.of(latest.getId()));
		String requestedChartType = explicitPartialPie ? "pie" : match.group(1);
		plan.getChanges().getPresentation().setChartType(requestedChartType.startsWith("饼") ? "pie" : "bar");
		plan.getChanges().getPresentation().setAllowPartialPie(
				plan.getChanges().getPresentation().getChartType().equals("pie") && explicitPartialPie);
		plan.setConfidence(1D);
		plan.setReason("仅修改图形，保留原查询结果和统计口径。");
		return plan;
	}

	private boolean isExplicitPartialPieRequest(String message) {
		return message.matches(".*(?:按|按照).*(?:现有|这些|这三项|三项|列出).*(?:指标|数据).*(?:自身|各自).*(?:占比|比例).*(?:饼状图|饼图)?.*")
				|| message.matches(".*(?:自身|各自).*(?:占比|比例).*(?:饼状图|饼图).*");
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
