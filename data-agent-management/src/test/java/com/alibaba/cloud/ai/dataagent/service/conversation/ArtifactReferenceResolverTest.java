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

import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactReferenceResolverTest {

	private final ArtifactReferenceResolver resolver = new ArtifactReferenceResolver();

	@Test
	void resolvesStableChartNumber() {
		ArtifactReferenceResolver.Resolution result = resolver.resolve("把图表 02 改成柱状图", charts());

		assertEquals(ArtifactReferenceResolver.Resolution.Status.RESOLVED, result.status());
		assertEquals("chart-2", result.artifact().getId());
		assertEquals("图表 02｜超差率趋势", result.label());
	}

	@Test
	void asksForClarificationWhenRelativeReferenceHasMultipleCharts() {
		ArtifactReferenceResolver.Resolution result = resolver.resolve("把上面这张图的上限改为 150", charts());

		assertEquals(ArtifactReferenceResolver.Resolution.Status.AMBIGUOUS, result.status());
		assertEquals(2, result.candidates().size());
	}

	@Test
	void listsAvailableChartsWhenNumberDoesNotExist() {
		ArtifactReferenceResolver.Resolution result = resolver.resolve("图表 03 的上限改为 150", charts());

		assertEquals(ArtifactReferenceResolver.Resolution.Status.NOT_FOUND, result.status());
		assertEquals(2, result.candidates().size());
	}

	@Test
	void ignoresReportArtifactThatCopiesTheSameChartPresentation() {
		AnalysisArtifact chart = AnalysisArtifact.builder().id("query-result").type("QUERY_RESULT")
				.userQuestion("工作中心目标完成率").presentationSpec("{\"type\":\"bar\",\"title\":\"工作中心目标完成率\"}")
				.createTime(LocalDateTime.of(2026, 9, 29, 10, 0)).build();
		AnalysisArtifact report = AnalysisArtifact.builder().id("report-copy").type("REPORT")
				.parentArtifactId("query-result").userQuestion("工作中心目标完成率")
				.presentationSpec("{\"type\":\"bar\",\"title\":\"工作中心目标完成率\"}")
				.createTime(LocalDateTime.of(2026, 9, 29, 10, 1)).build();

		ArtifactReferenceResolver.Resolution result = resolver.resolve("把上面这张图的上限改为 150",
				List.of(chart, report));

		assertEquals(ArtifactReferenceResolver.Resolution.Status.RESOLVED, result.status());
		assertEquals("query-result", result.artifact().getId());
	}

	@Test
	void usesSplitChartArtifactsInsteadOfTheirQueryResultParent() {
		AnalysisArtifact query = AnalysisArtifact.builder().id("query-result").type("QUERY_RESULT")
				.presentationSpec("{\"type\":\"bar\"}").createTime(LocalDateTime.of(2026, 9, 29, 10, 0)).build();
		AnalysisArtifact firstChart = AnalysisArtifact.builder().id("chart-1").type("CHART").parentArtifactId("query-result")
				.presentationSpec("{\"type\":\"bar\",\"title\":{\"text\":\"生产数量指标\"}}")
				.createTime(LocalDateTime.of(2026, 9, 29, 10, 1)).build();
		AnalysisArtifact secondChart = AnalysisArtifact.builder().id("chart-2").type("CHART").parentArtifactId("query-result")
				.presentationSpec("{\"type\":\"line\",\"title\":{\"text\":\"合格率\"}}")
				.createTime(LocalDateTime.of(2026, 9, 29, 10, 2)).build();

		ArtifactReferenceResolver.Resolution result = resolver.resolve("把上面这张图的上限改为 150",
				List.of(query, firstChart, secondChart));

		assertEquals(ArtifactReferenceResolver.Resolution.Status.AMBIGUOUS, result.status());
		assertEquals(2, result.candidates().size());
		assertEquals("图表 01｜生产数量指标", result.candidates().get(0).label());
		assertEquals("图表 02｜合格率", result.candidates().get(1).label());
	}

	private List<AnalysisArtifact> charts() {
		return List.of(
				AnalysisArtifact.builder().id("chart-1").type("CHART").userQuestion("尺寸趋势")
						.presentationSpec("{\"type\":\"line\",\"title\":\"尺寸趋势\"}")
						.createTime(LocalDateTime.of(2026, 9, 29, 10, 0)).build(),
				AnalysisArtifact.builder().id("chart-2").type("CHART").userQuestion("超差率趋势")
						.presentationSpec("{\"type\":\"line\",\"title\":\"超差率趋势\"}")
						.createTime(LocalDateTime.of(2026, 9, 29, 10, 1)).build());
	}
}
