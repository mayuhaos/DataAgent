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

import com.alibaba.cloud.ai.dataagent.dto.conversation.OperationPlan;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import org.junit.jupiter.api.Test;

class PresentationSpecEditorTest {

	@Test
	void pieToBarPreservesPointCounts() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("bar");
		String result = new PresentationSpecEditor().apply(
				"{\"series\":[{\"type\":\"pie\",\"data\":[{\"name\":\"合格\",\"value\":15},{\"name\":\"不合格\",\"value\":1}]}]}", changes);
		var spec = JsonUtil.getObjectMapper().readTree(result);
		assertEquals("bar", spec.at("/series/0/type").asText());
		assertEquals(15, spec.at("/series/0/data/0").asInt());
		assertEquals(1, spec.at("/series/0/data/1").asInt());
		assertEquals("合格", spec.at("/xAxis/data/0").asText());
	}

	@Test
	void pieExcludesCheckTotalButPreservesAllOutcomeCounts() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		String result = new PresentationSpecEditor().apply(
				"{\"dataAgent\":{\"verified\":true,\"mutuallyExclusive\":true},\"xAxis\":{\"data\":[\"检查数\",\"合格数\",\"返修数\",\"不合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[7688,6283,1389,16]}]}", changes);
		var spec = JsonUtil.getObjectMapper().readTree(result);
		assertEquals(3, spec.at("/series/0/data").size());
		assertEquals(6283, spec.at("/series/0/data/0/value").asInt());
		assertEquals(1389, spec.at("/series/0/data/1/value").asInt());
		assertEquals(16, spec.at("/series/0/data/2/value").asInt());
		org.junit.jupiter.api.Assertions.assertFalse(spec.has("xAxis"));
	}

	@Test
	void completePassFailCategoriesCanBecomePie() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		String result = new PresentationSpecEditor().apply(
				"{\"xAxis\":{\"data\":[\"合格数\",\"不合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[15,1]}]}", changes);
		var spec = JsonUtil.getObjectMapper().readTree(result);
		assertEquals(2, spec.at("/series/0/data").size());
		assertEquals(15, spec.at("/series/0/data/0/value").asInt());
		assertEquals(1, spec.at("/series/0/data/1/value").asInt());
	}

	@Test
	void partialIndicatorsReturnDifferenceAndRequireConfirmation() {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		var exception = org.junit.jupiter.api.Assertions.assertThrows(PresentationSpecEditor.PieConversionException.class,
				() -> new PresentationSpecEditor().apply(
						"{\"xAxis\":{\"data\":[\"检查总数\",\"合格数\",\"不合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[4309,2007,348]}]}", changes));
		assertEquals("4309", exception.getTotal().stripTrailingZeros().toPlainString());
		assertEquals("2355", exception.getParts().stripTrailingZeros().toPlainString());
		assertEquals("1954", exception.getDifference().stripTrailingZeros().toPlainString());
		org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("1954"));
	}

	@Test
	void explicitPartialPieConfirmationAllowsOwnIndicatorShares() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		changes.setAllowPartialPie(true);
		String result = new PresentationSpecEditor().apply(
				"{\"xAxis\":{\"data\":[\"检查总数\",\"合格数\",\"不合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[4309,2007,348]}]}", changes);
		var spec = JsonUtil.getObjectMapper().readTree(result);
		assertEquals(2, spec.at("/series/0/data").size());
		assertEquals("合格数、不合格数指标占比", spec.at("/title/text").asText());
	}

	@Test
	void possibleNestedIndicatorsRequireBusinessConfirmation() {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		var exception = org.junit.jupiter.api.Assertions.assertThrows(PresentationSpecEditor.PieConversionException.class,
				() -> new PresentationSpecEditor().apply(
						"{\"xAxis\":{\"data\":[\"检查总数\",\"一次合格数\",\"合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[4309,2007,3961]}]}", changes));
		org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("包含关系"));
		org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("一次合格数、合格数"));
	}

	@Test
	void incompatiblePieIsRejectedRatherThanInventingAPartition() {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setChartType("pie");
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new PresentationSpecEditor().apply(
				"{\"xAxis\":{\"data\":[\"检查数\",\"合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[7688,6283]}]}", changes));
	}

	@Test
	void updatesYAxisBoundWithoutChangingDataConfiguration() throws Exception {
		OperationPlan.PresentationChanges changes = new OperationPlan.PresentationChanges();
		changes.setYAxisMax(150D);

		String result = new PresentationSpecEditor().apply("{\"type\":\"line\",\"title\":\"尺寸趋势\"}", changes);

		assertEquals(150D, JsonUtil.getObjectMapper().readTree(result).path("yAxis").path("max").asDouble());
		assertEquals("line", JsonUtil.getObjectMapper().readTree(result).path("type").asText());
	}

	@Test
	void equalTotalDoesNotProveMutuallyExclusiveCategories() {
		var changes = new OperationPlan.PresentationChanges(); changes.setChartType("pie");
		assertEquals("分项合计等于总数，但尚未确认分类互斥，请确认业务分类关系。",
				org.junit.jupiter.api.Assertions.assertThrows(PresentationSpecEditor.PieConversionException.class,
						() -> new PresentationSpecEditor().apply("{\"xAxis\":{\"data\":[\"检查总数\",\"指标A\",\"指标B\"]},\"series\":[{\"type\":\"bar\",\"data\":[16,15,1]}]}", changes)).getMessage());
	}

	@Test
	void shareChangesRecalculateValuesAndDenominatorsWithoutChangingBaseData() throws Exception {
		String source = """
		{"xAxis":{"data":["杂质","前划","水滴"]},"yAxis":{"type":"value"},"series":[{"type":"bar","data":[3397,671,623]}],
		"dataAgent":{"verified":true,"mutuallyExclusive":true,"allTotal":6622,"baseItems":[
		{"id":"1","name":"杂质","value":3397},{"id":"2","name":"前划","value":671},{"id":"3","name":"水滴","value":623}]}}
		""";
		var editor = new PresentationSpecEditor();
		var all = com.alibaba.cloud.ai.dataagent.util.JsonUtil.getObjectMapper().readTree(editor.transformShares(source, "ALL"));
		assertEquals(51.30, all.at("/series/0/data/0").asDouble());
		assertEquals(6622, all.at("/dataAgent/denominator").asInt());
		var selected = com.alibaba.cloud.ai.dataagent.util.JsonUtil.getObjectMapper().readTree(editor.transformShares(all.toString(), "SELECTED"));
		assertEquals(72.42, selected.at("/series/0/data/0").asDouble());
		assertEquals(4691, selected.at("/dataAgent/denominator").asInt());
		assertEquals(3397, selected.at("/dataAgent/baseItems/0/value").asInt());
	}

	@Test
	void countChartRoundTripPreservesCountsAndMode() throws Exception {
		String source = """
		{"title":{"text":"缺陷数量"},"xAxis":{"data":["塑件缺陷","喷房缺陷","设备环境"]},
		"series":[{"type":"bar","data":[1375,542,4456]}],"dataAgent":{"verified":true,
		"mutuallyExclusive":true,"valueMode":"COUNT","denominatorScope":"SELECTED","denominator":6373,
		"baseItems":[{"id":"1","name":"塑件缺陷","value":1375},{"id":"3","name":"喷房缺陷","value":542},
		{"id":"4","name":"设备环境","value":4456}]}}
		""";
		var editor = new PresentationSpecEditor();
		var changes = new OperationPlan.PresentationChanges(); changes.setChartType("pie");
		var pie = JsonUtil.getObjectMapper().readTree(editor.apply(source, changes));
		assertEquals(1375, pie.at("/series/0/data/0/value").asInt());
		assertEquals("COUNT", pie.at("/dataAgent/valueMode").asText());
		changes.setChartType("bar"); changes.setYAxisMax(5000D);
		var bar = JsonUtil.getObjectMapper().readTree(editor.apply(pie.toString(), changes));
		assertEquals(1375, bar.at("/series/0/data/0").asInt());
		assertEquals(542, bar.at("/series/0/data/1").asInt());
		assertEquals(4456, bar.at("/series/0/data/2").asInt());
		assertEquals(5000D, bar.at("/yAxis/max").asDouble());
		assertEquals("COUNT", bar.at("/dataAgent/valueMode").asText());
	}

	@Test
	void percentageChartRoundTripPreservesAllDenominator() throws Exception {
		String source = """
		{"xAxis":{"data":["杂质","前划","水滴"]},"series":[{"type":"bar","data":[51.30,10.13,9.41]}],
		"dataAgent":{"verified":true,"mutuallyExclusive":true,"allTotal":6622,"denominator":6622,
		"denominatorScope":"ALL","valueMode":"PERCENTAGE","baseItems":[{"id":"1","name":"杂质","value":3397},
		{"id":"2","name":"前划","value":671},{"id":"3","name":"水滴","value":623}]}}
		""";
		var editor = new PresentationSpecEditor();
		var changes = new OperationPlan.PresentationChanges(); changes.setChartType("pie");
		String pie = editor.apply(source, changes);
		changes.setChartType("bar");
		var bar = JsonUtil.getObjectMapper().readTree(editor.apply(pie, changes));
		assertEquals(51.30, bar.at("/series/0/data/0").asDouble());
		assertEquals(10.13, bar.at("/series/0/data/1").asDouble());
		assertEquals(9.41, bar.at("/series/0/data/2").asDouble());
		assertEquals(6622, bar.at("/dataAgent/denominator").asInt());
		assertEquals("PERCENTAGE", bar.at("/dataAgent/valueMode").asText());
	}

	@Test
	void allDefectPercentagesConvertedToPieIncludeTheRemainder() throws Exception {
		String source = """
		{"xAxis":{"data":["杂质","前划","水滴"]},"yAxis":{"type":"value"},"series":[{"type":"bar","data":[51.30,10.13,9.41]}],
		"dataAgent":{"verified":true,"mutuallyExclusive":true,"allTotal":6622,"denominator":6622,"denominatorScope":"ALL","valueMode":"PERCENTAGE","percentage":true,"baseItems":[
		{"id":"1","name":"杂质","value":3397},{"id":"2","name":"前划","value":671},{"id":"3","name":"水滴","value":623}]}}
		""";
		var changes = new OperationPlan.PresentationChanges(); changes.setChartType("pie");
		var result = JsonUtil.getObjectMapper().readTree(new PresentationSpecEditor().apply(source, changes));
		assertEquals(4, result.at("/series/0/data").size());
		assertEquals(3397, result.at("/series/0/data/0/value").asInt());
		assertEquals(1931, result.at("/series/0/data/3/value").asInt());
		assertEquals(6622, result.at("/dataAgent/denominator").asInt());
	}
}
