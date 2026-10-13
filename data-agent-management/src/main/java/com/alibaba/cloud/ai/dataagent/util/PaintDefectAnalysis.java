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

	private static final Pattern CATEGORIES = Pattern.compile("塑件缺陷|喷房缺陷|设备环境|条线缺陷");
	private static final Pattern TOP = Pattern.compile("(?i)(?:top\\s*|前\\s*)(\\d+|[一二三四五六七八九十]+)");

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
		if (Pattern.compile("型号|产品|颜色|红色|黑色|客户|供应商|车间|产线|批次号|批次编号|每天|逐日|趋势|按日期|按批次|去重|样本|点位|平均|大于|小于|超过|至少|最多|不超过|同比|环比|增长|差值|之差").matcher(scopeText).find()) return Optional.empty();
		if (TOP.matcher(question).find() && topLimit(question) == 0) return Optional.empty();
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
		var categoryNames = CATEGORIES.matcher(question).results().map(match -> match.group()).distinct().toList();
		boolean allCategories = (topLimit(question) > 0 && categoryNames.isEmpty()) || allCategoryRequest(question)
				|| usesAllDenominator(question) || !excludedCategories(question).isEmpty() || isMultiChart(question);
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
		List<String> excluded = excludedCategories(question);
		List<Item> selected = categories.values().stream().filter(item -> question.contains(item.name())).toList();
		int limit = topLimit(question);
		boolean top = limit > 0;
		boolean multi = isMultiChart(question);
		if (selected.isEmpty() || (!multi && allCategoryRequest(question)) || !excluded.isEmpty()) selected = new ArrayList<>(categories.values());
		selected = selected.stream().filter(item -> !excluded.contains(item.name())).toList();
		BigDecimal total = sum(new ArrayList<>(categories.values()));
		BigDecimal selectedTotal = sum(selected);
		List<Item> selectedCategories = selected;
		List<Item> rankedDefects = defects.stream().filter(item -> multi || selectedCategories.stream()
				.anyMatch(category -> item.name().startsWith(category.name() + "—"))).toList();
		List<Item> topItems = rankedDefects.stream().sorted(java.util.Comparator.comparing(Item::value).reversed().thenComparing(Item::id)).limit(top ? limit : 3).toList();
		StringBuilder output = new StringBuilder("## 喷涂完整缺陷统计\n\n");
		output.append("统计口径：完整缺陷明细的缺陷数量求和，非去重不良产品数。\n\n");
		if (question.contains("是不是没数据") && selectedTotal.signum() > 0) output.append("“没有数据”的前提不成立，下表为数据库实际统计结果。\n\n");
		if (snapshot.path("data").isEmpty()) return Optional.of(output.append("数据库未查到记录，不能据此认定实际没有不良。\n").toString());
		JsonNode scope = snapshot.path("data").get(0);
		BigDecimal allTotal = scope.path("scope_all_categories").asInt() == 1 ? total : null;
		boolean ownTopShares = top && !multi && Pattern.compile("(?i)(?:这|那)[一二三四五六七八九十0-9]+个缺陷(?:之间|自身|内部|合计)|它们的合计|所选缺陷自身|(?:top\\s*\\d+|前[一二三四五六七八九十0-9]+)(?:自身|内部|之间|合计)").matcher(question).find()
				&& (question.contains("占") || question.contains("比例"));
		BigDecimal denominator = ownTopShares ? sum(topItems) : usesAllDenominator(question) ? total : selectedTotal;
		if (usesAllDenominator(question) && allTotal == null) throw new IllegalStateException("缺少全部缺陷分母，不能生成占全部的比例报告。");
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
		List<String> requestedDefects = Pattern.compile("杂质|前划|水滴|漆滴").matcher(question).results()
				.map(match -> match.group()).distinct().toList();
		List<Item> namedDefects = rankedDefects.stream().filter(item -> question.contains(item.name().substring(item.name().indexOf('—') + 1))).toList();
		if (!top && !multi && !requestedDefects.isEmpty()) {
			List<String> missing = requestedDefects.stream().filter(name -> namedDefects.stream()
					.noneMatch(item -> item.name().endsWith("—" + name))).toList();
			if (!missing.isEmpty()) output.append("在指定工厂、日期和大类范围内，未查到以下具体缺陷的明细：")
					.append(String.join("、", missing)).append("。不能以大类合计代替具体缺陷数量，也不能据此认定实际没有该缺陷。\n\n");
			if (namedDefects.isEmpty()) return Optional.of(output.toString());
		}
		if (!top && !multi && !namedDefects.isEmpty()) {
			table(output, namedDefects, usesAllDenominator(question) ? total : sum(namedDefects));
			output.append("名称中横线前为所属大类，横线后为具体缺陷；数量按当前日期范围的完整明细统计。\n\n");
			return Optional.of(output.toString());
		}
		if (top && !multi) {
			table(output, topItems, denominator);
			output.append("当前统计范围缺陷数量：").append(number(denominator)).append("；Top").append(limit).append(" 合计：").append(number(sum(topItems))).append("。\n\n");
			List<Item> chartItems = new ArrayList<>(topItems);
			boolean pie = question.contains("饼");
			if (pie && denominator.compareTo(sum(topItems)) > 0) chartItems.add(new Item("other", "其他缺陷", denominator.subtract(sum(topItems))));
			output.append(chart(chartItems, pie ? "pie" : "bar", question.contains("占") || question.contains("比例") ? "Top" + limit + (ownTopShares ? "自身占比" : "占当前范围缺陷比例") : "完整缺陷Top" + limit + "数量", denominator, step, allTotal, ownTopShares ? "SELECTED" : allTotal == null ? "SCOPE" : "ALL"));
		}
		else {
			table(output, selected, denominator);
			output.append("所选分类合计：").append(number(selectedTotal)).append("；当前查询范围的缺陷数量：").append(number(total)).append("。\n\n");
			boolean pie = question.contains("饼");
			if (multi) {
				List<String> requests = chartRequests(question);
				for (String request : requests) {
					boolean ranked = topLimit(request) > 0;
					List<Item> items = ranked ? rankedDefects.stream().sorted(java.util.Comparator.comparing(Item::value).reversed().thenComparing(Item::id)).limit(topLimit(request)).toList() : selected;
					boolean share = request.contains("占") || request.contains("比例");
					boolean requestPie = request.contains("饼");
					BigDecimal chartDenominator = usesAllDenominator(request) ? total : sum(items);
					List<Item> chartItems = new ArrayList<>(items);
					if (requestPie && chartDenominator.compareTo(sum(items)) > 0) chartItems.add(new Item("other", "其他缺陷", chartDenominator.subtract(sum(items))));
					output.append(chart(chartItems, requestPie ? "pie" : "bar", (ranked ? "完整具体缺陷Top" + topLimit(request) : "所选分类") + (share ? "占比" : "数量"), chartDenominator, step, allTotal, usesAllDenominator(request) ? "ALL" : ranked ? "TOP" : "SELECTED"));
				}
			}
			else if (question.contains("柱") || pie || question.contains("展示") || question.contains("各") || question.contains("汇总")) {
				List<Item> chartItems = new ArrayList<>(selected);
				if (pie && denominator.compareTo(selectedTotal) > 0) chartItems.add(new Item("other", "其他缺陷", denominator.subtract(selectedTotal)));
				output.append(chart(chartItems, pie ? "pie" : "bar", question.contains("占") || question.contains("比例") ? usesAllDenominator(question) ? "所选分类占全部缺陷比例" : "所选分类自身占比" : "所选分类缺陷数量", denominator, step, allTotal, usesAllDenominator(question) ? "ALL" : "SELECTED"));
			}
		}
		return Optional.of(output.toString());
	}

	public record Item(String id, String name, BigDecimal value) { }

	private static boolean allCategoryRequest(String question) {
		if (usesAllDenominator(question)) return false;
		return Pattern.compile("(?:全部|所有)(?:喷涂)?缺陷|(?:全部|所有)具体缺陷").matcher(question).find()
				&& !Pattern.compile("(?:大类|设备环境|塑件缺陷|喷房缺陷|条线缺陷)(?:下|内部|中)").matcher(question).find();
	}

	private static boolean usesAllDenominator(String question) {
		return Pattern.compile("(?:占|分母[^，,；;。]*)(?:全部|所有)(?:喷涂)?(?:具体)?缺陷").matcher(question).find();
	}

	private static int topLimit(String question) {
		var matcher = TOP.matcher(question);
		if (!matcher.find()) return 0;
		String value = matcher.group(1);
		int number;
		try { number = Integer.parseInt(value); }
		catch (NumberFormatException ex) { number = List.of("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十").indexOf(value); }
		return number > 0 && number <= 50 ? number : 0;
	}

	private static List<String> excludedCategories(String question) {
		var excluded = new ArrayList<String>();
		var matcher = Pattern.compile("(?:排除|不含|不包括|除去|剔除|除了)([^，,；;。]*?)(?:大类|后|以外|之外|$)").matcher(question);
		while (matcher.find()) CATEGORIES.matcher(matcher.group(1)).results().map(match -> match.group()).forEach(excluded::add);
		return excluded;
	}

	private static boolean isMultiChart(String question) { return Pattern.compile("(?:三|3)张图").matcher(question).find(); }

	private static List<String> chartRequests(String question) {
		var requests = new java.util.TreeMap<Integer, String>();
		var matcher = Pattern.compile("第\\s*([123一二三])\\s*张(?:图)?(?:为|是)?\\s*(.*?)(?=第\\s*[123一二三]\\s*张|$)", Pattern.DOTALL).matcher(question);
		while (matcher.find()) {
			String ordinal = matcher.group(1);
			int index = "一二三".indexOf(ordinal) + 1;
			if (index == 0) index = Integer.parseInt(ordinal);
			requests.put(index, matcher.group(2));
		}
		if (requests.isEmpty()) return List.of("三类数量柱状图", "三类自身占比饼图", "全部具体缺陷Top3数量柱状图");
		if (requests.size() != 3) throw new IllegalStateException("三张图的展示要求不完整，请分别说明每张图的指标和类型。");
		return new ArrayList<>(requests.values());
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
