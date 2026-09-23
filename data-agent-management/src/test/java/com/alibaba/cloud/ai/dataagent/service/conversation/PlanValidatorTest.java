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

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {

	private final PlanValidator validator = new PlanValidator();

	@Test
	void rejectsArtifactOutsideCurrentSessionCandidates() {
		OperationPlan plan = new OperationPlan();
		plan.getTarget().setArtifactIds(List.of("other-session-artifact"));

		OperationPlan result = validator.validate(plan, List.of(), List.of());

		assertEquals(OperationPlan.ExecutionMode.ASK_CLARIFICATION, result.getExecutionMode());
	}

	@Test
	void upgradesReuseWhenFiltersChange() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
		plan.getTarget().setArtifactIds(List.of("artifact-1"));
		plan.getChanges().getData().setFilters(java.util.Map.of("region", "华南"));
		AnalysisArtifact artifact = AnalysisArtifact.builder().id("artifact-1").build();

		OperationPlan result = validator.validate(plan, List.of(), List.of(artifact));

		assertEquals(OperationPlan.ExecutionMode.REQUERY, result.getExecutionMode());
	}

	@Test
	void upgradesLocalTransformWhenResultHasExpired() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.TRANSFORM_LOCAL);
		plan.getTarget().setArtifactIds(List.of("artifact-1"));
		AnalysisArtifact artifact = AnalysisArtifact.builder().id("artifact-1")
				.expireTime(LocalDateTime.now().minusMinutes(1)).build();

		OperationPlan result = validator.validate(plan, List.of(), List.of(artifact));

		assertEquals(OperationPlan.ExecutionMode.REQUERY, result.getExecutionMode());
	}

	@Test
	void upgradesLineChartReuseWhenThereAreTooFewDataPoints() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
		plan.getTarget().setArtifactIds(List.of("artifact-1"));
		plan.getChanges().getPresentation().setChartType("line");
		AnalysisArtifact artifact = AnalysisArtifact.builder().id("artifact-1").resultSample("[[\"2026-01\", 10]]")
				.build();

		OperationPlan result = validator.validate(plan, List.of(), List.of(artifact));

		assertEquals(OperationPlan.ExecutionMode.REQUERY, result.getExecutionMode());
	}

	@Test
	void upgradesLocalReuseWhenNoCompleteResultReferenceExists() {
		OperationPlan plan = new OperationPlan();
		plan.setExecutionMode(OperationPlan.ExecutionMode.REUSE);
		plan.getTarget().setArtifactIds(List.of("artifact-1"));

		OperationPlan result = validator.validate(plan, List.of(), List.of(AnalysisArtifact.builder().id("artifact-1").build()));

		assertEquals(OperationPlan.ExecutionMode.REQUERY, result.getExecutionMode());
	}

}
