package com.alibaba.cloud.ai.dataagent.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Detects lost aggregate aliases without inventing missing values or changing Python output. */
public final class PythonResultValidator {

	private static final Pattern ALIASED_SUM = Pattern.compile(
			"(?i)\\bSUM\\s*\\(\\s*(?:[`\\\"]?\\w+[`\\\"]?\\s*\\.\\s*)?[`\\\"]?(\\w+)[`\\\"]?\\s*\\)\\s+AS\\s+[`\\\"]?(\\w+)[`\\\"]?");

	private PythonResultValidator() {
	}

	public static void validateAliasedSums(String sql, List<Map<String, String>> rows, String output) {
		if (sql == null || rows.isEmpty()) {
			return;
		}
		JsonNode result;
		try {
			result = JsonUtil.getObjectMapper().readTree(output);
		}
		catch (Exception ignored) {
			return;
		}
		if (result == null || !result.isObject()) {
			return;
		}
		var matcher = ALIASED_SUM.matcher(sql);
		while (matcher.find()) {
			String source = matcher.group(1);
			String alias = matcher.group(2);
			// Only reject an explicit contradiction: the alias is correctly totaled in
			// output, but the same aggregate under its source name is reported as null.
			if (source.equalsIgnoreCase(alias) || !result.has(source) || !result.get(source).isNull()
					|| !result.hasNonNull(alias) || rows.stream().anyMatch(row -> row.containsKey(source))) {
				continue;
			}
			try {
				BigDecimal sum = BigDecimal.ZERO;
				for (var row : rows) {
					sum = sum.add(new BigDecimal(row.get(alias)));
				}
				if (sum.compareTo(new BigDecimal(result.get(alias).asText())) != 0) {
					continue;
				}
			}
			catch (NumberFormatException | NullPointerException ignored) {
				continue;
			}
			throw new IllegalStateException("Python字段匹配错误：SQL中SUM(" + source + ") AS " + alias
					+ "已返回有效数值，但Python将" + source + "输出为空。请使用实际返回字段" + alias
					+ "重新计算，不得将字段缺失解释为数据为空。");
		}
	}
}
