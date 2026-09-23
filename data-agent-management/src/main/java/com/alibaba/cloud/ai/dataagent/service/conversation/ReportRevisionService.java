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
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Revises a server-owned report without querying data again. */
@Service
@RequiredArgsConstructor
public class ReportRevisionService {

	private final AnalysisResultStore analysisResultStore;
	private final LlmService llmService;

	public String revise(AnalysisArtifact source, OperationPlan.ReportChanges changes) {
		if (source.getContentRef() == null) {
			throw new IllegalStateException("report content unavailable");
		}
		String original = analysisResultStore.get(source.getSessionId(), source.getContentRef());
		String style = changes == null || changes.getStyle() == null ? "保持原有风格" : changes.getStyle();
		String revised = llmService.blockToString(llmService.callUser(
				"仅重写下列报告正文，不得调用工具、生成 SQL 或添加未提供的数据。风格：" + style + "\n\n" + original));
		if (revised == null || revised.isBlank()) {
			throw new IllegalStateException("report revision returned no content");
		}
		return analysisResultStore.put(source.getSessionId(), revised, Duration.ofHours(1));
	}

}
