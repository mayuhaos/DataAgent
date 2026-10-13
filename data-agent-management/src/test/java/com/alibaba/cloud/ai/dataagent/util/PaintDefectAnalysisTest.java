package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaintDefectAnalysisTest {
	private SqlGenerationDTO input(String question) {
		SchemaDTO schema = new SchemaDTO();
		TableDTO detail = new TableDTO(); detail.setName("paint_defect_detail");
		TableDTO dictionary = new TableDTO(); dictionary.setName("paint_defect_dictionary");
		schema.setTable(List.of(detail, dictionary));
		return SqlGenerationDTO.builder().query(question).schemaDTO(schema).build();
	}

	@Test
	void monthlyAndDailyScopesUseFullChildToParentAggregates() {
		String sql = PaintDefectAnalysis.query(input("一厂2026年6月喷涂完整缺陷数量，各类汇总")).orElseThrow();
		assertTrue(sql.contains("SUM(d.defect_count)"));
		assertTrue(sql.contains("c.parent_id = p.id"));
		assertTrue(sql.contains("d.stat_date < '2026-07-01'"));
		assertFalse(sql.contains("LIMIT"));
		String daily = PaintDefectAnalysis.query(input("一厂2026年6月1日喷涂完整缺陷Top3")).orElseThrow();
		assertTrue(daily.contains("d.stat_date < '2026-06-02'"));
	}

	@Test
	void selectedCategoriesAreFilteredAndFactoryLookupIsNotOverridden() {
		String sql = PaintDefectAnalysis.query(input("一厂2026年6月塑件缺陷、喷房缺陷、设备环境完整缺陷数量")).orElseThrow();
		assertTrue(sql.contains("p.defect_name IN ('塑件缺陷','喷房缺陷','设备环境')"));
		assertTrue(sql.contains("OR c.id IS NULL OR p.id IS NULL"));
		assertTrue(sql.contains("0 AS scope_all_categories"));
		var lookup = input("一厂2026年6月喷涂完整缺陷"); lookup.setExecutionDescription("从quality_factory查询一厂的factory_id");
		assertTrue(PaintDefectAnalysis.query(lookup).isEmpty());
		lookup.setExecutionDescription("从paint_defect_detail汇总缺陷数量"); lookup.setScopeRestricted(true);
		assertTrue(PaintDefectAnalysis.query(lookup).isEmpty());
	}

	@Test
	void abstainsFromAmbiguousTimeAndAdditionalFilters() {
		for (String question : List.of("一厂6月喷涂完整缺陷", "二厂2026年6月喷涂缺陷", "一厂2026年6月红色产品喷涂缺陷", "一厂2026年6月喷涂缺陷每天趋势")) {
			assertTrue(PaintDefectAnalysis.query(input(question)).isEmpty(), question);
		}
	}

	private String snapshot() {
		return """
		{"column":["quality_snapshot_version"],"data":[
		{"category_id":"1","category":"塑件缺陷","defect_id":"11","defect":"前划","defect_total":"58","scope_start_date":"2026-06-01","scope_end_date":"2026-06-02"},
		{"category_id":"3","category":"喷房缺陷","defect_id":"12","defect":"缺陷B","defect_total":"61"},
		{"category_id":"4","category":"设备环境","defect_id":"13","defect":"杂质","defect_total":"215"}]}
		""";
	}

	@Test
	void rendersEachCategoryExactlyAndProducesThreeBoundCharts() {
		String result = PaintDefectAnalysis.report("一厂2026年6月1日，塑件缺陷、喷房缺陷、设备环境，生成三张图", Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(result.contains("| 1 | 塑件缺陷 | 58 |"));
		assertTrue(result.contains("| 3 | 喷房缺陷 | 61 |"));
		assertTrue(result.contains("| 4 | 设备环境 | 215 |"));
		assertTrue(result.contains("334"));
		assertEquals(3, result.split("```echarts", -1).length - 1);
		assertTrue(result.contains("\"sourceStep\":\"step_1\""));
	}

	@Test
	void missingSpecificDefectCannotBeReplacedWithItsParentTotal() {
		String report = PaintDefectAnalysis.report("查询一厂2026年6月喷涂设备环境大类中的前划完整明细数量",
				Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("未查到"));
		assertTrue(report.contains("前划"));
		assertFalse(report.contains("| 4 | 设备环境 | 215 |"));
		assertFalse(report.contains("塑件缺陷—前划"));
	}

	@Test
	void redundantMonthRangeDoesNotDisableTemplateButDifferentRangeDoes() {
		assertTrue(PaintDefectAnalysis.query(input("查询一厂2026年6月（2026-06-01至2026-06-30）喷涂完整缺陷数量")).isPresent());
		assertTrue(PaintDefectAnalysis.query(input("查询一厂2026年6月（2026-06-01至2026-06-15）喷涂完整缺陷数量")).isEmpty());
	}

	@Test
	void allCategoriesIncludingOneNamedCategoryDoesNotFilterReport() {
		String report = PaintDefectAnalysis.report("一厂2026年6月所有缺陷大类，包括设备环境", Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("| 1 | 塑件缺陷 | 58 |"));
		assertTrue(report.contains("| 3 | 喷房缺陷 | 61 |"));
		assertTrue(report.contains("| 4 | 设备环境 | 215 |"));
	}

	@Test
	void recordQueryUsesDetailOnlyAndDoesNotRenderNullAsZero() {
		var request = input("一厂2099年1月1日喷涂完整缺陷明细有几条记录？");
		request.getSchemaDTO().setTable(List.of(request.getSchemaDTO().getTable().get(0)));
		assertTrue(PaintDefectAnalysis.query(request).orElseThrow().contains("COUNT(*) AS record_count"));
		String report = PaintDefectAnalysis.report("记录数", Map.of("step_1", "{\"column\":[\"quality_record_snapshot_version\"],\"data\":[{\"record_count\":0,\"defect_total\":null}]}")).orElseThrow();
		assertTrue(report.contains("0 条记录"));
		assertTrue(report.contains("NULL"));
		assertFalse(report.contains("```echarts"));
	}

	@Test
	void allChildrenOfOneParentDoNotMeanAllParents() {
		String question = "一厂2026年6月设备环境大类下全部具体缺陷的不良数量合计是多少";
		assertTrue(PaintDefectAnalysis.query(input(question)).orElseThrow().contains("p.defect_name IN ('设备环境')"));
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("设备环境 | 215 |"));
		assertFalse(report.contains("塑件缺陷 | 58 |"));
	}

	@Test
	void namedChildReportKeepsParentAndDailyQuantity() {
		String report = PaintDefectAnalysis.report("这里的前划属于什么大类？这一日数量是多少？", Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("塑件缺陷—前划 | 58 |"));
		assertFalse(report.contains("设备环境 | 215 |"));
	}

	@Test
	void refusesTruncatedOrInconsistentSnapshots() {
		assertThrows(IllegalStateException.class, () -> PaintDefectAnalysis.report("设备环境", Map.of("step_1", snapshot().replace("\"category\":\"塑件缺陷\"", "\"category\":null"))));
		assertThrows(IllegalStateException.class, () -> PaintDefectAnalysis.report("缺陷", Map.of("step_1", snapshot().replace("\"column\"", "\"truncated\":true,\"column\""))));
		assertThrows(IllegalStateException.class, () -> PaintDefectAnalysis.report("缺陷", Map.of("step_1", snapshot(), "step_2", snapshot().replace("\"58\"", "\"71\""))));
	}

	@Test
	void topWithinParentKeepsParentFilterAndDoesNotRankOtherParents() {

		String question = "查询一厂2026年6月喷涂设备环境大类内部的真实具体缺陷Top3，按完整明细统计。";
		assertTrue(PaintDefectAnalysis.query(input(question)).orElseThrow().contains("p.defect_name IN ('设备环境')"));
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		assertFalse(report.contains("塑件缺陷—前划"));
		assertTrue(report.contains("比例分母：215"));
		assertTrue(report.contains("\"denominatorScope\":\"SCOPE\""));
		assertFalse(report.contains("\"allTotal\""));
	}

	@Test
	void topOneUsesExactlyOneItem() {
		String question = "一厂2026年6月喷涂全部完整具体缺陷Top1数量，用柱状图展示";
		assertTrue(PaintDefectAnalysis.query(input(question)).isPresent());
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("设备环境—杂质"));
		assertFalse(report.contains("塑件缺陷—前划"));
		assertFalse(report.contains("喷房缺陷—缺陷B"));
	}

	@Test
	void selectedCategoriesCanUseFullDenominator() {
		String question = "一厂2026年6月塑件缺陷、喷房缺陷各占全部喷涂缺陷数量的比例，用柱状图展示";
		assertFalse(PaintDefectAnalysis.query(input(question)).orElseThrow().contains("p.defect_name IN"));
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot().replace("\"scope_start_date\"", "\"scope_all_categories\":1,\"scope_start_date\""))).orElseThrow();
		assertTrue(report.contains("比例分母：334"));
		assertTrue(report.contains("17.37%"));
		assertTrue(report.contains("18.26%"));
		assertFalse(report.contains("| 4 | 设备环境"));
	}

	@Test
	void exclusionDoesNotIncludeTheExcludedParent() {
		String question = "一厂2026年6月喷涂全部完整缺陷明细，排除设备环境大类后，各大类数量和总计是多少";
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("所选分类合计：119"));
		assertFalse(report.contains("| 4 | 设备环境"));
	}

	@Test
	void orderedChartsFollowUserOrderInsteadOfFixedThreeChartLayout() {
		String question = "一厂2026年6月喷涂完整缺陷明细，统计塑件缺陷、喷房缺陷、设备环境，生成三张图：第1张为全部具体缺陷Top3数量柱状图；第2张为三类数量柱状图；第3张为三类自身占比饼图。";
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		int firstChart = report.indexOf("```echarts");
		int secondChart = report.indexOf("```echarts", firstChart + 1);
		assertTrue(report.substring(firstChart, secondChart).contains("设备环境—杂质"));
		assertFalse(report.substring(firstChart, secondChart).contains("\"name\":\"设备环境\""));
	}

	@Test
	void parentInternalTopDoesNotMeanTopInternalDenominator() {
		String question = "一厂2026年6月喷涂设备环境大类内部的真实具体缺陷Top3各占设备环境合计多少，用柱状图展示";
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot())).orElseThrow();
		assertTrue(report.contains("比例分母：215"));
		assertTrue(report.contains("\"denominatorScope\":\"SCOPE\""));
	}

	@Test
	void topOwnSharesUseTopSumAndKeepFullDenominatorForLaterEdits() {
		String question = "一厂2026年6月喷涂全部具体缺陷Top3，在这三个缺陷之间比较占比，用柱状图展示";
		assertTrue(PaintDefectAnalysis.query(input(question)).isPresent());
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot().replace("\"scope_start_date\"", "\"scope_all_categories\":1,\"scope_start_date\""))).orElseThrow();
		assertTrue(report.contains("\"denominatorScope\":\"SELECTED\""));
		assertTrue(report.contains("\"allTotal\":334"));
	}

	@Test
	void namedDefectCanUseFullDenominatorInsteadOfItsOwnValue() {
		String question = "一厂2026年6月喷涂杂质占全部缺陷多少";
		String report = PaintDefectAnalysis.report(question, Map.of("step_1", snapshot().replace("\"scope_start_date\"", "\"scope_all_categories\":1,\"scope_start_date\""))).orElseThrow();
		assertTrue(report.contains("比例分母：334"));
		assertTrue(report.contains("64.37%"));
	}

	@Test
	void doesNotIgnoreAdditionalConditionsOrUnrecognizedRanks() {
		for (String question : List.of("一厂2026年6月喷涂完整缺陷数量大于100的具体缺陷", "一厂2026年6月喷涂完整缺陷Top0", "一厂2026年6月喷涂完整缺陷Top1000", "一厂2026年6月喷涂完整缺陷平均每天数量")) {
			assertTrue(PaintDefectAnalysis.query(input(question)).isEmpty(), question);
		}
	}
}
