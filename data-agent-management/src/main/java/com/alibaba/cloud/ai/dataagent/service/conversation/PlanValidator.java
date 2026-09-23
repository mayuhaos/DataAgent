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
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Enforces local ownership and reuse constraints before any execution is considered. */
@Component
public class PlanValidator {

	public OperationPlan validate(OperationPlan plan, Collection<ConversationTopic> topics,
			Collection<AnalysisArtifact> artifacts) {
		if (plan == null || plan.getTarget() == null || plan.getChanges() == null) {
			return clarification("无法确认要引用的历史分析对象。");
		}
		Set<String> topicIds = topics.stream().map(ConversationTopic::getId).collect(Collectors.toSet());
		Set<String> artifactIds = artifacts.stream().map(AnalysisArtifact::getId).collect(Collectors.toSet());
		if (plan.getTarget().getTopicIds() == null) {
			plan.getTarget().setTopicIds(java.util.List.of());
		}
		if (plan.getTarget().getArtifactIds() == null) {
			plan.getTarget().setArtifactIds(java.util.List.of());
		}
		if (!topicIds.containsAll(plan.getTarget().getTopicIds()) || !artifactIds.containsAll(plan.getTarget().getArtifactIds())) {
			return clarification("引用的主题或分析结果不属于当前会话。");
		}
		if ((plan.getExecutionMode() == OperationPlan.ExecutionMode.REUSE
				|| plan.getExecutionMode() == OperationPlan.ExecutionMode.TRANSFORM_LOCAL)
				&& hasDataChange(plan)) {
			plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
			plan.setReason("指标、维度、筛选、时间或粒度变化，不能复用旧结果。");
		}
		if (requiresCompleteResult(plan) && artifacts.stream().filter(a -> plan.getTarget().getArtifactIds().contains(a.getId()))
				.anyMatch(this::isResultUnavailable)) {
			plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
			plan.setReason("引用结果已过期，必须按当前权限重新查询。");
		}
		if (plan.getExecutionMode() == OperationPlan.ExecutionMode.REUSE && requestsLineChart(plan)
				&& !hasRenderableTrend(plan, artifacts)) {
			plan.setExecutionMode(OperationPlan.ExecutionMode.REQUERY);
			plan.setReason("当前结果缺少可绘制趋势的有序字段或至少两个有效数据点。");
		}
		return plan;
	}

	private boolean hasDataChange(OperationPlan plan) {
		OperationPlan.DataChanges data = plan.getChanges().getData();
		if (data == null) {
			return true;
		}
		if (data.getMetrics() == null) {
			data.setMetrics(java.util.List.of());
		}
		if (data.getDimensions() == null) {
			data.setDimensions(java.util.List.of());
		}
		return !data.getMetrics().isEmpty() || !data.getDimensions().isEmpty() || data.getFilters() != null
				|| data.getTimeRange() != null || data.getGrain() != null || data.getSort() != null || data.getLimit() != null;
	}

	private boolean requiresCompleteResult(OperationPlan plan) {
		return plan.getExecutionMode() == OperationPlan.ExecutionMode.TRANSFORM_LOCAL
				|| plan.getExecutionMode() == OperationPlan.ExecutionMode.REUSE;
	}

	private boolean isResultUnavailable(AnalysisArtifact artifact) {
		return artifact.getResultRef() == null
				|| (artifact.getExpireTime() != null && artifact.getExpireTime().isBefore(LocalDateTime.now()));
	}

	private boolean requestsLineChart(OperationPlan plan) {
		return plan.getChanges().getPresentation() != null
				&& "line".equalsIgnoreCase(plan.getChanges().getPresentation().getChartType());
	}

	private boolean hasRenderableTrend(OperationPlan plan, Collection<AnalysisArtifact> artifacts) {
		for (AnalysisArtifact artifact : artifacts) {
			if (!plan.getTarget().getArtifactIds().contains(artifact.getId())) {
				continue;
			}
			try {
				if (JsonUtil.getObjectMapper().readTree(artifact.getResultSample()).size() >= 2) {
					return true;
				}
			}
			catch (Exception ignored) {
				return false;
			}
		}
		return false;
	}

	private OperationPlan clarification(String question) {
		OperationPlan result = new OperationPlan();
		result.setOperation(OperationPlan.Operation.ASK_CLARIFICATION);
		result.setExecutionMode(OperationPlan.ExecutionMode.ASK_CLARIFICATION);
		result.setClarificationQuestion(question);
		result.setReason(question);
		return result;
	}

}
