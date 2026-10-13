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
import com.alibaba.cloud.ai.dataagent.util.ReportFactsProtector;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Revises a server-owned report without querying data again. */
@Service
@RequiredArgsConstructor
public class ReportRevisionService {

	private final AnalysisResultStore analysisResultStore;
	private final LlmService llmService;

	public String getContent(String sessionId, String contentRef) {
		return analysisResultStore.get(sessionId, contentRef);
	}

	public String renderChart(AnalysisArtifact source, String presentationSpec) {
		String content = "## 图表已更新\n\n```echarts\n" + presentationSpec
				+ "\n```\n\n" + "数据来源沿用原查询结果；指标和分母以当前图表为准。\n";
		return analysisResultStore.put(source.getSessionId(), content, Duration.ofHours(1));
	}

	public String revise(AnalysisArtifact source, OperationPlan.ReportChanges changes) {
		if (source.getContentRef() == null) {
			throw new IllegalStateException("report content unavailable");
		}
		String original = analysisResultStore.get(source.getSessionId(), source.getContentRef());
		var protectedReport = ReportFactsProtector.protect(original);
		String style = changes == null || changes.getStyle() == null ? "保持原有风格" : changes.getStyle();
		String revised = llmService.blockToString(llmService.callUser(
				"仅调整下列报告的非数据文字风格，不得添加数值、表格、代码、图表或新业务结论。"
						+ "每个 DATA_AGENT_FACT_ 占位符代表不可修改的数据块，必须原样保留一次，独占一行，不得加列表符号、缩进或代码围栏。"
						+ "只返回调整后的报告。风格：" + style + "\n\n" + protectedReport.editable()));
		if (revised == null || revised.isBlank()) {
			throw new IllegalStateException("report revision returned no content");
		}
		return analysisResultStore.put(source.getSessionId(), protectedReport.restore(revised), Duration.ofHours(1));
	}

}
