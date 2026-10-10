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
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Applies display-only changes without changing result data or the original query. */
@Component
public class PresentationSpecEditor {

	public String apply(String sourceSpec, OperationPlan.PresentationChanges changes) {
		try {
			ObjectNode spec = parse(sourceSpec);
			if (changes.getChartType() != null) {
				if (!spec.path("series").isArray()) throw new IllegalArgumentException("rendered chart data unavailable");
				convertChart(spec, changes.getChartType(), changes);
			}
			putIfPresent(spec, "type", changes.getChartType());
			putIfPresent(spec, "x", changes.getX());
			if (changes.getTitle() != null && spec.path("series").isArray()) {
				spec.with("title").put("text", changes.getTitle());
			}
			else putIfPresent(spec, "title", changes.getTitle());
			if (changes.getY() != null && !changes.getY().isEmpty()) {
				spec.set("y", JsonUtil.getObjectMapper().valueToTree(changes.getY()));
			}
			setAxis(spec, "xAxis", changes.getXAxisMin(), changes.getXAxisMax());
			setAxis(spec, "yAxis", changes.getYAxisMin(), changes.getYAxisMax());
			if (changes.getLegendVisible() != null) {
				spec.with("legend").put("show", changes.getLegendVisible());
			}
			return JsonUtil.getObjectMapper().writeValueAsString(spec);
		}
		catch (Exception ex) {
			if (ex instanceof PieConversionException pieException) {
				throw pieException;
			}
			throw new IllegalArgumentException("invalid presentation change", ex);
		}
	}

	private void convertChart(ObjectNode spec, String requestedType, OperationPlan.PresentationChanges changes) {
		String type = "column".equals(requestedType) ? "bar" : requestedType;
		if (!"pie".equals(type) && !"bar".equals(type)) {
			throw new IllegalArgumentException("unsupported chart conversion");
		}
		JsonNode series = spec.path("series");
		if (series.size() != 1) throw new IllegalArgumentException("conversion requires one series");
		JsonNode oldSeries = series.get(0);
		JsonNode data = oldSeries.path("data");
		if (!data.isArray() || data.isEmpty()) throw new IllegalArgumentException("chart data unavailable");
		var names = JsonUtil.getObjectMapper().createArrayNode();
		var values = JsonUtil.getObjectMapper().createArrayNode();
		var pie = JsonUtil.getObjectMapper().createArrayNode();
		var categoryNames = new java.util.ArrayList<String>();
		boolean allowPartialPie = Boolean.TRUE.equals(changes.getAllowPartialPie());
		java.math.BigDecimal total = null;
		java.math.BigDecimal parts = java.math.BigDecimal.ZERO;
		for (int i = 0; i < data.size(); i++) {
			JsonNode item = data.get(i);
			String name = item.isObject() ? item.path("name").asText()
					: spec.path("xAxis").path("data").path(i).asText();
			JsonNode value = item.isObject() ? item.path("value") : item;
			if (name.isBlank() || !value.isNumber() || ("pie".equals(type) && value.asDouble() < 0)) {
				throw new IllegalArgumentException("chart requires named numeric categories");
			}
			if ("pie".equals(type) && isTotal(name)) {
				total = decimalValue(value);
				continue;
			}
			if ("pie".equals(type)) {
				parts = parts.add(decimalValue(value));
			}
			names.add(name);
			values.add(value);
			pie.addObject().put("name", name).set("value", value);
			categoryNames.add(name);
		}
		if ("pie".equals(type)) {
			java.util.List<String> overlapNames = potentialOverlapNames(categoryNames);
			if (total != null && !allowPartialPie && !overlapNames.isEmpty()) {
				throw new PieConversionException(
						"当前分类可能存在包含关系：" + String.join("、", overlapNames)
								+ "。程序不能仅根据字段名称判断是否重复计算，请确认分类互斥后再生成饼图，或选择按现有指标自身占比展示。",
					null, parts, null);
			}
			if (total != null && !allowPartialPie && total.compareTo(parts) != 0) {
				java.math.BigDecimal difference = total.subtract(parts).abs();
				throw new PieConversionException(
						"当前数据包含检查总数和部分结果指标，明细合计为 " + format(parts)
								+ "，与检查总数 " + format(total) + " 不一致，差额为 " + format(difference)
								+ "。无法直接生成完整构成饼图。请选择：按现有指标自身占比展示，或补充其余 "
								+ format(difference) + " 的分类。",
						total, parts, difference);
			}
			if (total == null && !allowPartialPie && !isKnownCompletePartition(categoryNames)) {
				throw new PieConversionException(
						"当前图表没有可证明完整且互斥的分类，无法直接生成构成饼图。请确认分类互斥且完整，或明确要求按现有指标自身占比展示。",
						null, parts, null);
			}
		}
		ObjectNode next = JsonUtil.getObjectMapper().createObjectNode();
		next.put("type", type);
		next.set("data", "pie".equals(type) ? pie : values);
		next.putObject("label").put("show", true)
				.put("formatter", "pie".equals(type) ? "{b}: {c} ({d}%)" : "{c}");
		spec.putArray("series").add(next);
		spec.with("tooltip").remove("formatter");
		spec.with("legend").set("data", names);
		if ("pie".equals(type)) {
			if (allowPartialPie && total != null && (changes.getTitle() == null || changes.getTitle().isBlank())) {
				spec.with("title").put("text", String.join("、", categoryNames) + "指标占比");
			}
			spec.remove(java.util.List.of("xAxis", "yAxis", "grid"));
			spec.with("tooltip").put("trigger", "item");
			spec.with("tooltip").put("formatter", "{b}: {c} ({d}%)");
		}
		else {
			spec.putObject("xAxis").put("type", "category").set("data", names);
			spec.putObject("yAxis").put("type", "value");
			spec.with("tooltip").put("trigger", "axis");
			spec.with("legend").put("show", false);
		}
	}

	private java.math.BigDecimal decimalValue(JsonNode value) {
		return value.decimalValue();
	}

	private String format(java.math.BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private boolean isKnownCompletePartition(java.util.List<String> names) {
		if (names.size() != 2) {
			return false;
		}
		java.util.Set<String> normalized = names.stream().map(this::normalizeCategory).collect(java.util.stream.Collectors.toSet());
		return normalized.equals(java.util.Set.of("合格", "不合格"))
				|| normalized.equals(java.util.Set.of("通过", "不通过"));
	}

	private String normalizeCategory(String name) {
		return name.replaceAll("数$", "").replaceAll("率$", "").trim();
	}

	private java.util.List<String> potentialOverlapNames(java.util.List<String> names) {
		java.util.List<String> overlaps = new java.util.ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			String left = normalizeOverlapCategory(names.get(i));
			for (int j = i + 1; j < names.size(); j++) {
				String right = normalizeOverlapCategory(names.get(j));
				if (!left.isBlank() && left.equals(right)) {
					overlaps.add(names.get(i) + "、" + names.get(j));
				}
			}
		}
		return overlaps;
	}

	private String normalizeOverlapCategory(String name) {
		return name.replaceAll("^(一次|二次|三次|累计|总)?", "")
				.replaceAll("(数|率)$", "").trim();
	}

	private boolean isTotal(String name) {
		return name.matches("总数|总点位数|总点位|测量点位总数|检查数|检查总数|总检查数|总计|合计|total(_.*)?");
	}

	static class PieConversionException extends IllegalArgumentException {

		private final java.math.BigDecimal total;

		private final java.math.BigDecimal parts;

		private final java.math.BigDecimal difference;

		PieConversionException(String message, java.math.BigDecimal total, java.math.BigDecimal parts,
				java.math.BigDecimal difference) {
			super(message);
			this.total = total;
			this.parts = parts;
			this.difference = difference;
		}

		java.math.BigDecimal getTotal() {
			return total;
		}

		java.math.BigDecimal getParts() {
			return parts;
		}

		java.math.BigDecimal getDifference() {
			return difference;
		}
	}

	private ObjectNode parse(String sourceSpec) throws Exception {
		JsonNode parsed = sourceSpec == null || sourceSpec.isBlank() ? null
				: JsonUtil.getObjectMapper().readTree(sourceSpec);
		return parsed instanceof ObjectNode object ? object.deepCopy() : JsonUtil.getObjectMapper().createObjectNode();
	}

	private void setAxis(ObjectNode spec, String key, Double min, Double max) {
		if (min == null && max == null) {
			return;
		}
		ObjectNode axis = spec.with(key);
		if (min != null) axis.put("min", min);
		if (max != null) axis.put("max", max);
	}

	private void putIfPresent(ObjectNode target, String key, String value) {
		if (value != null && !value.isBlank()) {
			target.put(key, value);
		}
	}
}
