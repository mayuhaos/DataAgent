package com.alibaba.cloud.ai.dataagent.dto.conversation;

import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationPlanTest {
	@Test
	void axisNamesRoundTripAndAcceptLegacyAliases() throws Exception {
		for (String name : new String[] {"yAxisMax", "yaxisMax"}) {
			var changes = JsonUtil.getObjectMapper().readValue("{\"" + name + "\":5000}", OperationPlan.PresentationChanges.class);
			assertEquals(5000D, changes.getYAxisMax());
			var json = JsonUtil.getObjectMapper().valueToTree(changes);
			assertEquals(5000, json.path("yAxisMax").asInt());
			assertFalse(json.has("yaxisMax"));
		}
	}
}
