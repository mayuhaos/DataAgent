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
				"{\"xAxis\":{\"data\":[\"检查数\",\"合格数\",\"返修数\",\"不合格数\"]},\"series\":[{\"type\":\"bar\",\"data\":[7688,6283,1389,16]}]}", changes);
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
}
