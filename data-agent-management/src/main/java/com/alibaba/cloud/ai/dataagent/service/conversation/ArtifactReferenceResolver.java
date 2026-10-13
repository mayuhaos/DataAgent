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

import com.alibaba.cloud.ai.dataagent.entity.AnalysisArtifact;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Resolves user-visible chart references before the LLM plans an edit. */
@Component
public class ArtifactReferenceResolver {

	private static final Pattern CHART_NUMBER = Pattern.compile("(?:图表|图)\\s*(\\d+)");
	private static final Pattern ORDINAL_CHART = Pattern.compile("第\\s*([一二三四五六七八九十0-9]+)\\s*(?:(?:张|个)\\s*图?|图)");
	private static final Pattern RELATIVE_CHART = Pattern.compile("上面(?:这)?[个张]?图|这[个张]图|刚才(?:那)?张图|上一张图");

	public Resolution resolve(String userMessage, List<AnalysisArtifact> artifacts) {
		List<AnalysisArtifact> charts = chartsInStableOrder(artifacts);
		if (charts.isEmpty()) {
			return Resolution.none();
		}
		String currentReport = provenanceValue(charts.get(charts.size() - 1), "reportId");
		if (currentReport != null) charts = charts.stream()
				.filter(chart -> currentReport.equals(provenanceValue(chart, "reportId"))).toList();
		List<AnalysisArtifact> scopedCharts = charts;
		Matcher number = CHART_NUMBER.matcher(userMessage);
		if (number.find()) {
			return byNumber(charts, Integer.parseInt(number.group(1)));
		}
		Matcher ordinal = ORDINAL_CHART.matcher(userMessage);
		if (ordinal.find()) {
			return byNumber(charts, toNumber(ordinal.group(1)));
		}

		List<AnalysisArtifact> titleMatches = charts.stream()
			.filter(artifact -> mentionsTitle(userMessage, title(artifact)))
			.toList();
		if (titleMatches.size() == 1) {
			return Resolution.resolved(titleMatches.get(0), label(charts, titleMatches.get(0)), "图表名称");
		}
		if (titleMatches.size() > 1) {
			return Resolution.ambiguous(cards(scopedCharts, titleMatches));
		}
		if (RELATIVE_CHART.matcher(userMessage).find()) {
			AnalysisArtifact latest = charts.get(charts.size() - 1);
			List<AnalysisArtifact> recent = charts.stream().filter(chart -> java.util.Objects.equals(
					chartGroup(chart), chartGroup(latest))).toList();
			if (recent.size() == 1) {
				return Resolution.resolved(latest, label(charts, latest), "最近报表的唯一图表");
			}
			return Resolution.ambiguous(cards(charts, recent));
		}
		return Resolution.none();
	}

	public List<Card> cards(List<AnalysisArtifact> artifacts) {
		List<AnalysisArtifact> charts = chartsInStableOrder(artifacts);
		return cards(charts, charts);
	}

	static String chartGroup(AnalysisArtifact artifact) {
		String reportId = provenanceValue(artifact, "reportId");
		if (reportId != null) return reportId;
		return "QUERY_RESULT".equals(artifact.getType()) ? artifact.getId() : artifact.getParentArtifactId();
	}

	private Resolution byNumber(List<AnalysisArtifact> charts, int number) {
		if (number < 1 || number > charts.size()) {
			return Resolution.notFound(cards(charts, charts));
		}
		AnalysisArtifact chart = charts.get(number - 1);
		return Resolution.resolved(chart, label(charts, chart), "图表编号");
	}

	private List<AnalysisArtifact> chartsInStableOrder(List<AnalysisArtifact> artifacts) {
		Comparator<AnalysisArtifact> fallbackOrder = Comparator
			.comparing(AnalysisArtifact::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(AnalysisArtifact::getId, Comparator.nullsLast(Comparator.naturalOrder()));
		return artifacts.stream()
			.filter(artifact -> isChart(artifact) && !isSupersededQueryResult(artifact, artifacts)
					&& !isSupersededChart(artifact, artifacts))
			.sorted(Comparator
				.comparing((AnalysisArtifact artifact) -> groupCreateTime(artifact, artifacts),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingInt(this::chartOrder)
				.thenComparing(fallbackOrder))
			.toList();
	}

	private java.time.LocalDateTime groupCreateTime(AnalysisArtifact artifact, List<AnalysisArtifact> artifacts) {
		String reportId = provenanceValue(artifact, "reportId");
		if (reportId != null) {
			return artifacts.stream().filter(candidate -> reportId.equals(provenanceValue(candidate, "reportId")))
					.map(AnalysisArtifact::getCreateTime).filter(java.util.Objects::nonNull)
					.min(java.util.Comparator.naturalOrder()).orElse(artifact.getCreateTime());
		}
		String sourceQueryResultId = provenanceValue(artifact, "sourceQueryResultId");
		if (sourceQueryResultId != null) {
			return artifacts.stream().filter(candidate -> sourceQueryResultId.equals(candidate.getId()))
				.map(AnalysisArtifact::getCreateTime).filter(java.util.Objects::nonNull).findFirst()
				.orElse(artifact.getCreateTime());
		}
		if (artifact.getParentArtifactId() != null) {
			return artifacts.stream().filter(candidate -> artifact.getParentArtifactId().equals(candidate.getId()))
				.map(AnalysisArtifact::getCreateTime).filter(java.util.Objects::nonNull).findFirst()
				.orElse(artifact.getCreateTime());
		}
		return artifact.getCreateTime();
	}

	private int chartOrder(AnalysisArtifact artifact) {
		String value = provenanceValue(artifact, "chartOrder");
		if (value == null) {
			return Integer.MAX_VALUE;
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ignored) {
			return Integer.MAX_VALUE;
		}
	}

	private static String provenanceValue(AnalysisArtifact artifact, String field) {
		if (artifact.getProvenance() == null || artifact.getProvenance().isBlank()) {
			return null;
		}
		try {
			JsonNode provenance = JsonUtil.getObjectMapper().readTree(artifact.getProvenance());
			JsonNode value = provenance == null ? null : provenance.get(field);
			return value == null || value.isNull() ? null : value.asText();
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private boolean isSupersededQueryResult(AnalysisArtifact artifact, List<AnalysisArtifact> artifacts) {
		return "QUERY_RESULT".equalsIgnoreCase(artifact.getType()) && artifacts.stream()
				.anyMatch(candidate -> "CHART".equalsIgnoreCase(candidate.getType())
						&& artifact.getId().equals(candidate.getParentArtifactId()));
	}

	private boolean isSupersededChart(AnalysisArtifact artifact, List<AnalysisArtifact> artifacts) {
		return "CHART".equalsIgnoreCase(artifact.getType()) && artifacts.stream()
				.anyMatch(candidate -> "CHART".equalsIgnoreCase(candidate.getType())
						&& artifact.getId().equals(candidate.getParentArtifactId()));
	}

	private boolean isChart(AnalysisArtifact artifact) {
		// A report persists the same presentation spec as its source query result so it
		// can be reproduced, but it is not an independently rendered/editable chart.
		if ("REPORT".equalsIgnoreCase(artifact.getType())) {
			return false;
		}
		if ("CHART".equalsIgnoreCase(artifact.getType())) {
			return true;
		}
		if (!"QUERY_RESULT".equalsIgnoreCase(artifact.getType())) {
			return false;
		}
		try {
			JsonNode spec = JsonUtil.getObjectMapper().readTree(artifact.getPresentationSpec());
			return spec != null && spec.hasNonNull("type") && !"table".equalsIgnoreCase(spec.path("type").asText());
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private boolean mentionsTitle(String message, String title) {
		if (title.isBlank()) {
			return false;
		}
		String normalizedMessage = message.toLowerCase(Locale.ROOT);
		String normalizedTitle = title.toLowerCase(Locale.ROOT);
		return normalizedMessage.contains(normalizedTitle)
			|| (normalizedTitle.length() >= 4 && normalizedMessage.contains(normalizedTitle.replace("趋势", "")));
	}

	private String title(AnalysisArtifact artifact) {
		try {
			JsonNode spec = JsonUtil.getObjectMapper().readTree(artifact.getPresentationSpec());
			JsonNode titleNode = spec == null ? null : spec.path("title");
			String title = titleNode == null ? "" : titleNode.isObject() ? titleNode.path("text").asText("")
					: titleNode.asText("");
			return title.isBlank() ? java.util.Objects.toString(artifact.getUserQuestion(), "") : title;
		}
		catch (Exception ignored) {
			return artifact.getUserQuestion() == null ? "" : artifact.getUserQuestion();
		}
	}

	private String label(List<AnalysisArtifact> charts, AnalysisArtifact artifact) {
		return "图表 %02d｜%s".formatted(charts.indexOf(artifact) + 1, title(artifact));
	}

	private List<Card> cards(List<AnalysisArtifact> charts, List<AnalysisArtifact> selected) {
		List<Card> cards = new ArrayList<>();
		for (AnalysisArtifact artifact : selected) {
			cards.add(new Card(artifact.getId(), label(charts, artifact), title(artifact)));
		}
		return cards;
	}

	private int toNumber(String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ignored) {
			return "一二三四五六七八九十".indexOf(value) + 1;
		}
	}

	public record Card(String artifactId, String label, String title) { }

	public record Resolution(Status status, AnalysisArtifact artifact, String label, String reason, List<Card> candidates) {
		public enum Status { NONE, RESOLVED, AMBIGUOUS, NOT_FOUND }

		static Resolution none() { return new Resolution(Status.NONE, null, null, null, List.of()); }

		static Resolution resolved(AnalysisArtifact artifact, String label, String reason) {
			return new Resolution(Status.RESOLVED, artifact, label, reason, List.of());
		}

		static Resolution ambiguous(List<Card> candidates) {
			return new Resolution(Status.AMBIGUOUS, null, null, null, candidates);
		}

		static Resolution notFound(List<Card> candidates) {
			return new Resolution(Status.NOT_FOUND, null, null, null, candidates);
		}

		public Optional<AnalysisArtifact> resolvedArtifact() { return Optional.ofNullable(artifact); }
	}
}
