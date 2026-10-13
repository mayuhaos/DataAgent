package com.alibaba.cloud.ai.dataagent.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportFactsProtectorTest {

	@Test
	void styleChangesPreservePointCountsTableChartAndNumericConclusion() {
		String original = "# 检查结果\n\n合格15个点位，不合格1个点位。\n\n| 结果 | 数量 |\n| --- | --- |\n| 合格 | 15 |\n| 不合格 | 1 |\n\n```echarts\n{\"series\":[{\"type\":\"bar\",\"data\":[15,1]}]}\n```\n\n建议复核不合格点位。\n";
		var report = ReportFactsProtector.protect(original);
		assertFalse(report.editable().contains("合格15"));
		assertEquals(original.replace("建议复核不合格点位。", "请复核异常点位。"),
				report.restore(report.editable().replace("建议复核不合格点位。", "请复核异常点位。")));
	}

	@Test
	void rejectsTheFortyEightAndSixteenRewriteAndMissingOrDuplicatedFacts() {
		var report = ReportFactsProtector.protect("# 检查结果\n\n合格15，不合格1。\n");
		assertThrows(ReportFactsProtector.FactChangeException.class, () -> report.restore("# 检查结果\n合格48，不合格16。"));
		assertThrows(ReportFactsProtector.FactChangeException.class, () -> report.restore(report.editable() + "\n合格48，不合格16。"));
		String marker = report.facts().keySet().iterator().next();
		assertThrows(ReportFactsProtector.FactChangeException.class, () -> report.restore(report.editable() + "\n" + marker));
	}

	@Test
	void protectsNestedListDataDatesAndAlternativeFences() {
		String original = "# 2026年6月\n\n- 合格15\n- 不合格1\n\n~~~echarts\n{\"series\":[]}\n~~~\n";
		var report = ReportFactsProtector.protect(original);
		assertEquals(original, report.restore(report.editable()));
		assertFalse(report.editable().contains("2026年"));
		assertFalse(report.editable().contains("echarts"));
	}
}
