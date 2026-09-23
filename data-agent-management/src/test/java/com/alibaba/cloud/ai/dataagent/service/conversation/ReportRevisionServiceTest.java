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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportRevisionServiceTest {

	@Mock private AnalysisResultStore resultStore;
	@Mock private LlmService llmService;

	@Test
	void revisesStoredReportWithoutReadingDatabase() {
		when(resultStore.get("s1", "report-ref")).thenReturn("# Sales\nConclusion");
		when(llmService.blockToString(any())).thenReturn("# Concise Sales");
		when(resultStore.put(eq("s1"), any(), any(Duration.class))).thenReturn("new-report-ref");
		AnalysisArtifact source = AnalysisArtifact.builder().sessionId("s1").contentRef("report-ref").build();

		OperationPlan.ReportChanges changes = new OperationPlan.ReportChanges();
		changes.setStyle("concise");
		assertEquals("new-report-ref", new ReportRevisionService(resultStore, llmService).revise(source, changes));
	}

}
