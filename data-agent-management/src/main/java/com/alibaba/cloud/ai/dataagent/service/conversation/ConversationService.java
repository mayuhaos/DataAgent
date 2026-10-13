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
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
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
		if (request.getUserMessage().matches("(?s).*缺陷.*(?:所以|就是|等于).*不良产品.*(?:对吗|吗).*")) {
			return clarification(session, sessionId, request.getUserMessage(),
					"不能把缺陷数量直接当作不良产品件数。完整缺陷明细的 SUM(defect_count) 是缺陷数量合计，COUNT(*) 才是记录条数；同一产品可能有多个缺陷。统计去重不良产品数需要产品唯一标识和明确去重口径。");
		}
		if (topics.isEmpty() && !scopeContext.containsKey("year") && !scopeContext.containsKey("defaultYear")
				&& request.getUserMessage().matches("(?s).*\\d{1,2}月.*(?:喷涂|缺陷).*")
				&& !request.getUserMessage().matches("(?s).*\\d{2}(?:\\d{2})?年.*")) {
			return clarification(session, sessionId, request.getUserMessage(), "请确认要查询哪一年的数据；当前没有可继承的年份。");
		}
		boolean createRequest = isCreateRequest(request.getUserMessage());
		ArtifactReferenceResolver.Resolution reference = createRequest ? ArtifactReferenceResolver.Resolution.none()
				: artifactReferenceResolver.resolve(request.getUserMessage(), artifacts);
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
		OperationPlan planned = createRequest ? null : chartOnlyPlan(request.getUserMessage(), artifacts, reference);
		if (planned == null && !createRequest) planned = sharePlan(request.getUserMessage(), artifacts, reference);
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
				if (derivedArtifact == null && plan.getExecutionMode() != OperationPlan.ExecutionMode.NO_EXECUTION) {
					throw new IllegalStateException("no derived artifact was created");
				}
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
			if (plan.getChanges().getData().getDenominatorScope() != null) {
				artifact.setPresentationSpec(presentationSpecEditor.transformShares(artifact.getPresentationSpec(),
						plan.getChanges().getData().getDenominatorScope()));
				artifact.setResultRef(localResultTransformer.storeChartValues(source, artifact.getPresentationSpec()));
				artifact.setResultSchema("[\"category\",\"value\"]");
				artifact.setResultSample(null);
				artifact.setResultSummary("{\"derived\":true,\"denominatorScope\":\"" + plan.getChanges().getData().getDenominatorScope() + "\"}");
				artifact.setContentRef(reportRevisionService.renderChart(source, artifact.getPresentationSpec()));
			}
			else artifact.setResultRef(localResultTransformer.transform(source, plan.getChanges().getData()));
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
		String normalized = message.trim().replaceAll("[，,；;。]", " ").trim();
		java.util.regex.Matcher axis = java.util.regex.Pattern.compile(
				"(?i)([xy])\\s*轴\\s*(上限|下限)\\s*(?:设置为|设为|改为|改成|设置成|调整为|为)?\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(normalized);
		boolean axisChange = axis.find();
		String displayRequest = axisChange ? normalized.substring(0, axis.start()).trim() : normalized;
		displayRequest = displayRequest.replaceAll("^(?:只|再)\\s*", "").trim();
		java.util.regex.Matcher match = java.util.regex.Pattern.compile(
				"^(?:(?:把|将)?(?:第\\s*[一二三四五六七八九十0-9]+\\s*(?:(?:张|个)\\s*图?|图)|图表\\s*\\d+|上面这个图|上面的图|这个图|这张图|上面这张图|上一个图|饼状图|柱状图)\\s*)?"
				+ "(?:换成|改成|改为|改回|调整为|用)\\s*(饼状图|饼图|柱状图|柱形图)(?:展示|显示|回答)?[！!\\s]*$")
				.matcher(displayRequest);
		boolean chartChange = match.matches();
		boolean axisOnly = axisChange && displayRequest.matches("^(?:(?:把|将)?(?:第\\s*[一二三四五六七八九十0-9]+\\s*(?:(?:张|个)\\s*图?|图)|图表\\s*\\d+|上面这个图|上面的图|这个图|这张图|上面这张图))?$");
		boolean explicitPartialPie = isExplicitPartialPieRequest(message);
		if (!chartChange && !axisOnly && !explicitPartialPie) return null;
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
		String requestedChartType = explicitPartialPie ? "pie" : chartChange ? match.group(1) : null;
		if (requestedChartType != null) plan.getChanges().getPresentation().setChartType(requestedChartType.startsWith("饼") ? "pie" : "bar");
		plan.getChanges().getPresentation().setAllowPartialPie(
				"pie".equals(plan.getChanges().getPresentation().getChartType()) && explicitPartialPie);
		if (axisChange) {
			double bound = Double.parseDouble(axis.group(3));
			boolean upper = "上限".equals(axis.group(2));
			var presentation = plan.getChanges().getPresentation();
			if ("x".equalsIgnoreCase(axis.group(1))) {
				if (upper) presentation.setXAxisMax(bound); else presentation.setXAxisMin(bound);
			}
			else {
				if (upper) presentation.setYAxisMax(bound); else presentation.setYAxisMin(bound);
			}
		}
		plan.setConfidence(1D);
		plan.setReason("仅修改图形，保留原查询结果和统计口径。");
		return plan;
	}

	private boolean isExplicitPartialPieRequest(String message) {
		return message.matches(".*(?:按|按照).*(?:现有|这些|这三项|三项|列出).*(?:指标|数据).*(?:自身|各自).*(?:占比|比例).*(?:饼状图|饼图)?.*")
				|| message.matches(".*(?:自身|各自).*(?:占比|比例).*(?:饼状图|饼图).*");
	}

	private boolean isCreateRequest(String message) {
		return message.matches("(?s).*(?:查询|统计|分析).*(?:生成|创建|新建).*(?:图|报表|报告).*")
				|| message.matches("(?s)^\\s*(?:请|帮我)?(?:生成|创建|新建).*(?:图|报表|报告).*");
	}

	private OperationPlan sharePlan(String message, List<AnalysisArtifact> artifacts,
			ArtifactReferenceResolver.Resolution reference) {
		if (message.matches("(?s).*(?:查询|统计|\\d{2}年|\\d月|一厂|二厂).*")
				|| !message.matches("(?s).*(?:占比|比例).*") || artifacts.isEmpty()) return null;
		String scope = message.matches("(?s).*(?:全部|所有)缺陷.*") ? "ALL"
				: message.matches("(?si).*(?:top\\s*3|前三|三项|三类|三个缺陷).*(?:内部|自身|各自|之间|合计).*|.*只比较.*top\\s*3.*") ? "SELECTED" : null;
		if (scope == null) return null;
		AnalysisArtifact source = reference.resolvedArtifact().orElseGet(() -> artifacts.stream()
				.filter(a -> "CHART".equals(a.getType()))
				.max(java.util.Comparator.comparing(AnalysisArtifact::getCreateTime,
						java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).orElse(null));
		if (source == null) return null;
		OperationPlan plan = new OperationPlan();
		try {
			var metadata = JsonUtil.getObjectMapper().readTree(source.getPresentationSpec()).path("dataAgent");
			if (message.contains("饼") && "SELECTED".equals(scope)
					&& "SELECTED".equals(metadata.path("denominatorScope").asText())
					&& "COUNT".equals(metadata.path("valueMode").asText())) {
				plan.setOperation(OperationPlan.Operation.REVISE);
				plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
				plan.getTarget().setArtifactIds(List.of(source.getId()));
				plan.getChanges().getPresentation().setChartType("pie");
				plan.setReason("使用原数量作为饼图权重，保留原统计口径和分母。");
				return plan;
			}
		}
		catch (Exception ignored) { }
		plan.setOperation(OperationPlan.Operation.TRANSFORM);
		plan.setExecutionMode(OperationPlan.ExecutionMode.TRANSFORM_LOCAL);
		plan.getTarget().setArtifactIds(List.of(source.getId()));
		plan.getChanges().getData().setDenominatorScope(scope);
		plan.setReason("按明确分母重新计算比例，并校验数据来源。");
		if (message.contains("饼")) plan.getChanges().getPresentation().setChartType("pie");
		if (message.contains("柱")) plan.getChanges().getPresentation().setChartType("bar");
		return plan;
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
