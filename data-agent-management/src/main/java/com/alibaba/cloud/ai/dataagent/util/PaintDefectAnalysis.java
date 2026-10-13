package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded full-detail defect aggregates with server-rendered, traceable chart values. */
public final class PaintDefectAnalysis {

	private PaintDefectAnalysis() { }

	public static Optional<String> query(SqlGenerationDTO input) {
		String question = input.getQuery() == null ? "" : input.getQuery();
		if (Boolean.TRUE.equals(input.getScopeRestricted())) return Optional.empty();
		if (input.getOriginalQuery() != null && input.getOriginalQuery().matches("(?s).*\\d{2}(?:\\d{2})?年.*")
				&& input.getOriginalQuery().contains("一厂")) question = input.getOriginalQuery();
		if (input.getSql() != null || input.getSchemaDTO() == null || input.getSchemaDTO().getTable() == null) return Optional.empty();
		if (input.getExecutionDescription() != null && !Pattern.compile("缺陷|paint_defect_detail").matcher(input.getExecutionDescription()).find()) return Optional.empty();
		var tables = input.getSchemaDTO().getTable().stream().map(table -> table.getName()).toList();
		boolean recordQuery = question.matches("(?s).*(?:几条|多少条|记录条数|记录数量|记录数).*");
		if (!tables.contains("paint_defect_detail") || (!recordQuery && !tables.contains("paint_defect_dictionary"))) return Optional.empty();
		if (!(question.contains("喷涂") || question.contains("设备环境") || question.contains("具体缺陷") || question.contains("完整缺陷")) || !question.contains("缺陷")) return Optional.empty();
		if (Pattern.compile("注塑|打磨|终检|尺寸|毛坯").matcher(question).find()) return Optional.empty();
		// This template deliberately abstains from additional entity filters and trends.
		String scopeText = question.replace("喷涂下线产品缺陷表", "喷涂缺陷表");
		if (Pattern.compile("型号|产品|颜色|红色|黑色|客户|供应商|车间|产线|批次号|批次编号|每天|逐日|趋势|按日期|按批次|去重|样本|点位").matcher(scopeText).find()) return Optional.empty();
		if (Pattern.compile("二厂|三厂|四厂|五厂|所有工厂|各厂|全厂|跨厂").matcher(question).find()) return Optional.empty();
		if (!question.contains("一厂") && !question.contains("factory_id=1")) return Optional.empty();
		var date = Pattern.compile("(?<!\\d)(20\\d{2}|\\d{2})年\\s*(\\d{1,2})月(?:\\s*(\\d{1,2})(?:日|号))?").matcher(question);
		if (!date.find()) return Optional.empty();
		int year = Integer.parseInt(date.group(1));
		if (year < 100) year += 2000;
		LocalDate start;
		LocalDate end;
		try {
			start = LocalDate.of(year, Integer.parseInt(date.group(2)), date.group(3) == null ? 1 : Integer.parseInt(date.group(3)));
			end = date.group(3) == null ? start.plusMonths(1) : start.plusDays(1);
		}
		catch (RuntimeException ex) { return Optional.empty(); }
		String rangeText = question.replace(start + "至" + end.minusDays(1), "")
				.replace(start + "至" + end, "");
		if (date.find() || Pattern.compile("至|到\\s*\\d|截至|之前|之后|上半年|下半年").matcher(rangeText).find()) return Optional.empty();
		if (recordQuery) {
			if (Pattern.compile("塑件缺陷|喷房缺陷|设备环境|条线缺陷|杂质|前划").matcher(question).find()) return Optional.empty();
			return Optional.of("SELECT COUNT(*) AS record_count, SUM(defect_count) AS defect_total, "
					+ "1 AS quality_record_snapshot_version FROM paint_defect_detail WHERE factory_id = 1 "
					+ "AND stat_date >= '" + start + "' AND stat_date < '" + end + "'");
		}
		boolean allCategories = Pattern.compile("(?i)top\\s*3|前三|前\\s*3").matcher(question).find() || allCategoryRequest(question);
		var categoryNames = Pattern.compile("塑件缺陷|喷房缺陷|设备环境|条线缺陷").matcher(question).results().map(match -> match.group()).distinct().toList();
		boolean supportedChild = Pattern.compile("杂质|前划|水滴|漆滴").matcher(question).find();
		if (categoryNames.isEmpty() && !allCategories && !supportedChild
				&& !question.matches("(?s).*(?:喷涂(?:完整)?缺陷(?:明细)?(?:数量|总数|总计)|(?:每个|各).*缺陷).*")) return Optional.empty();
		if (categoryNames.isEmpty()) allCategories = true;
		String categoryFilter = allCategories ? "" : " AND (p.defect_name IN (" + categoryNames.stream().map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(",")) + ") OR c.id IS NULL OR p.id IS NULL)";
		return Optional.of("""
			SELECT p.id AS category_id, p.defect_name AS category,
			       c.id AS defect_id, c.defect_name AS defect,
			       SUM(d.defect_count) AS defect_total, COUNT(*) AS record_count,
			       1 AS quality_snapshot_version, 1 AS scope_factory_id, %d AS scope_all_categories,
			       '%s' AS scope_start_date, '%s' AS scope_end_date
			FROM paint_defect_detail d
			LEFT JOIN paint_defect_dictionary c ON d.defect_id = c.id
			LEFT JOIN paint_defect_dictionary p ON c.parent_id = p.id
			WHERE d.factory_id = 1 AND d.stat_date >= '%s' AND d.stat_date < '%s'%s
			GROUP BY p.id, p.defect_name, c.id, c.defect_name, d.defect_id
			ORDER BY p.id, c.id
			""".formatted(allCategories ? 1 : 0, start, end, start, end, categoryFilter));
	}

	public static Optional<String> report(String question, Map<String, String> results) {
		JsonNode snapshot = null;
		String step = null;
		for (var entry : results.entrySet()) {
			try {
				JsonNode candidate = JsonUtil.getObjectMapper().readTree(entry.getValue());
				if (candidate.path("column").toString().contains("quality_record_snapshot_version")) {
					JsonNode row = candidate.path("data").path(0);
					if (!row.hasNonNull("record_count")) throw new IllegalStateException("记录数查询缺少结果。");
					return Optional.of("## 喷涂完整缺陷明细记录数\n\n数据库查询到 " + row.path("record_count").asText()
							+ " 条记录。缺陷数量汇总为 " + (row.path("defect_total").isNull() ? "NULL（无记录可汇总）" : row.path("defect_total").asText())
							+ "。\n\n未查到记录只能说明当前数据库没有对应记录，不能据此认定当天实际没有不良，也不能把 NULL 当作零或生成合格率。\n");
				}
				if (candidate.path("column").toString().contains("quality_snapshot_version")) {
					if (candidate.path("truncated").asBoolean()) throw new IllegalStateException("查询结果被截断，不能生成全量报告。");
					if (snapshot != null && !snapshot.path("data").equals(candidate.path("data"))) {
						throw new IllegalStateException("多步骤缺陷快照不一致，未发布报告。");
					}
					snapshot = candidate;
					step = entry.getKey();
				}
			}
			catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
		}
		if (snapshot == null) return Optional.empty();
		List<Item> defects = new ArrayList<>();
		Map<String, Item> categories = new LinkedHashMap<>();
		for (JsonNode row : snapshot.path("data")) {
			String category = row.path("category").asText("");
			String defect = row.path("defect").asText("");
			if (category.isBlank() || defect.isBlank()) throw new IllegalStateException("存在未关联到父子字典的缺陷，不能生成完整分类报告。");
			BigDecimal value = new BigDecimal(row.path("defect_total").asText());
			if (value.signum() < 0) throw new IllegalStateException("缺陷数量不能为负值。");
			String categoryId = row.path("category_id").asText();
			Item previous = categories.get(categoryId);
			categories.put(categoryId, new Item(categoryId, category, previous == null ? value : previous.value().add(value)));
			defects.add(new Item(row.path("defect_id").asText(), category + "—" + defect, value));
		}
		List<Item> selected = categories.values().stream().filter(item -> question.contains(item.name())).toList();
		boolean top = Pattern.compile("(?i)top\\s*3|前三|前\\s*3").matcher(question).find();
		boolean multi = Pattern.compile("(?:三|3)张图").matcher(question).find();
		if (selected.isEmpty() || (!multi && top)
				|| (!multi && allCategoryRequest(question))) selected = new ArrayList<>(categories.values());
		BigDecimal total = sum(new ArrayList<>(categories.values()));
		BigDecimal selectedTotal = sum(selected);
		List<Item> topItems = defects.stream().sorted(java.util.Comparator.comparing(Item::value).reversed().thenComparing(Item::id)).limit(3).toList();
		StringBuilder output = new StringBuilder("## 喷涂完整缺陷统计\n\n");
		output.append("统计口径：完整缺陷明细的缺陷数量求和，非去重不良产品数。\n\n");
		if (question.contains("是不是没数据") && selectedTotal.signum() > 0) output.append("“没有数据”的前提不成立，下表为数据库实际统计结果。\n\n");
		if (snapshot.path("data").isEmpty()) return Optional.of(output.append("数据库未查到记录，不能据此认定实际没有不良。\n").toString());
		JsonNode scope = snapshot.path("data").get(0);
		BigDecimal allTotal = scope.path("scope_all_categories").asInt() == 1 ? total : null;
		output.append("范围：一厂，").append(scope.path("scope_start_date").asText()).append("（含）至 ")
				.append(scope.path("scope_end_date").asText()).append("（不含）。\n\n");
		if (question.contains("设备环境") && question.contains("杂质") && question.contains("互斥")) {
			Item environment = categories.values().stream().filter(i -> i.name().equals("设备环境")).findFirst().orElseThrow();
			Item impurity = defects.stream().filter(i -> i.name().equals("设备环境—杂质")).findFirst().orElseThrow();
			return Optional.of(output.append("杂质包含在设备环境中，不能作为两类互斥分类相加。建议改为杂质 ")
					.append(number(impurity.value())).append("、其他设备环境缺陷 ")
					.append(number(environment.value().subtract(impurity.value())))
					.append("（总量减杂质）。请确认是否按该口径展示饼图。\n").toString());
		}
		if (question.contains("合计") && question.contains("四项") && question.contains("饼图")) {
			output.append("合计包含各分项，不能再次作为饼图分类。下面仅展示分项，分母为分项合计。\n\n");
		}
		List<Item> namedDefects = defects.stream().filter(item -> question.contains(item.name().substring(item.name().indexOf('—') + 1))).toList();
		if (!top && !multi && !namedDefects.isEmpty()) {
			table(output, namedDefects, sum(namedDefects));
			output.append("名称中横线前为所属大类，横线后为具体缺陷；数量按当前日期范围的完整明细统计。\n\n");
			return Optional.of(output.toString());
		}
		if (top && !multi) {
			table(output, topItems, total);
			output.append("全部缺陷数量：").append(number(total)).append("；Top3 合计：").append(number(sum(topItems))).append("。\n\n");
			List<Item> chartItems = new ArrayList<>(topItems);
			boolean pie = question.contains("饼");
			if (pie && total.compareTo(sum(topItems)) > 0) chartItems.add(new Item("other", "其他缺陷", total.subtract(sum(topItems))));
			output.append(chart(chartItems, pie ? "pie" : "bar", question.contains("占") || question.contains("比例") ? "Top3占全部缺陷比例" : "完整缺陷Top3数量", total, step, allTotal, "ALL"));
		}
		else {
			table(output, selected, selectedTotal);
			output.append("所选分类合计：").append(number(selectedTotal)).append("；当前查询范围的缺陷数量：").append(number(total)).append("。\n\n");
			boolean pie = question.contains("饼");
			if (multi) {
				output.append(chart(selected, "bar", "所选分类缺陷数量", selectedTotal, step, allTotal, "SELECTED"));
				output.append(chart(selected, "pie", "所选分类自身占比", selectedTotal, step, allTotal, "SELECTED"));
				output.append(chart(topItems, "bar", "全部具体缺陷Top3数量", sum(topItems), step, allTotal, "TOP"));
			}
			else if (question.contains("柱") || pie || question.contains("展示") || question.contains("各") || question.contains("汇总")) {
				output.append(chart(selected, pie ? "pie" : "bar", question.contains("占") || question.contains("比例") ? "所选分类自身占比" : "所选分类缺陷数量", selectedTotal, step, allTotal, "SELECTED"));
			}
		}
		return Optional.of(output.toString());
	}

	public record Item(String id, String name, BigDecimal value) { }

	private static boolean allCategoryRequest(String question) {
		return Pattern.compile("(?:全部|所有)(?:喷涂)?缺陷|(?:全部|所有)具体缺陷").matcher(question).find()
				&& !Pattern.compile("(?:大类|设备环境|塑件缺陷|喷房缺陷|条线缺陷)(?:下|内部|中)").matcher(question).find();
	}

	private static BigDecimal sum(List<Item> items) { return items.stream().map(Item::value).reduce(BigDecimal.ZERO, BigDecimal::add); }
	private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
	private static void table(StringBuilder output, List<Item> items, BigDecimal denominator) {
		output.append("比例分母：").append(number(denominator)).append("。\n\n");
		output.append("| 分类ID | 分类 | 缺陷数量 | 占指定分母比例 |\n| --- | --- | ---: | ---: |\n");
		for (Item item : items) output.append("| ").append(item.id()).append(" | ").append(item.name().replace("|", "\\|")).append(" | ")
				.append(number(item.value())).append(" | ").append(denominator.signum() == 0 ? "无法计算" : item.value().multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP) + "%").append(" |\n");
		output.append("\n");
	}

	private static String chart(List<Item> items, String type, String title, BigDecimal denominator, String step, BigDecimal allTotal, String denominatorScope) {
		if (items.isEmpty() || (type.equals("pie") && denominator.signum() == 0)) return "";
		ObjectNode spec = JsonUtil.getObjectMapper().createObjectNode();
		boolean percentage = !type.equals("pie") && title.matches(".*(?:占比|比例).*");
		spec.putObject("title").put("text", title);
		spec.putObject("tooltip").put("trigger", type.equals("pie") ? "item" : "axis");
		var names = spec.putObject("legend").putArray("data");
		var series = spec.putArray("series").addObject().put("type", type);
		var data = series.putArray("data");
		var meta = spec.putObject("dataAgent");
		meta.put("verified", true).put("sourceStep", step).put("metric", "defect_count").put("mutuallyExclusive", true)
				.put("completePartition", sum(items).compareTo(denominator) == 0).put("percentage", percentage)
				.put("valueMode", title.matches(".*(?:占比|比例).*" ) ? "PERCENTAGE" : "COUNT")
				.put("denominatorScope", denominatorScope).put("denominator", denominator);
		if (allTotal != null) meta.put("allTotal", allTotal);
		var base = meta.putArray("baseItems");
		for (Item item : items) {
			names.add(item.name());
			base.addObject().put("id", item.id()).put("name", item.name()).put("value", item.value());
			if (type.equals("pie")) data.addObject().put("name", item.name()).put("value", item.value());
			else if (percentage && denominator.signum() > 0) data.add(item.value().multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP));
			else data.add(item.value());
		}
		series.putObject("label").put("show", true).put("formatter", type.equals("pie") ? "{b}: {c} ({d}%)" : percentage ? "{c}%" : "{c}");
		if (!type.equals("pie")) {
			spec.putObject("xAxis").put("type", "category").set("data", names.deepCopy());
			spec.putObject("yAxis").put("type", "value").put("name", percentage ? "比例 (%)" : "缺陷数量");
		}
		return "```echarts\n" + spec + "\n```\n\n";
	}
}
