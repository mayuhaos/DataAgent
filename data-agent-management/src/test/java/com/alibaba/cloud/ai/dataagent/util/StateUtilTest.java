package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;

class StateUtilTest {

	@Test
	void explicitDenominatorChangeSurvivesARewrittenCanonicalQuestion() {
		var enhanced = new QueryEnhanceOutputDTO();
		enhanced.setCanonicalQuery("一厂2026年6月设备环境大类Top3占同一范围缺陷数量的比例");
		var state = new OverAllState();
		state.registerKeyAndStrategy(QUERY_ENHANCE_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(REQUESTED_DENOMINATOR_SCOPE, new ReplaceStrategy());
		state.updateState(Map.of(QUERY_ENHANCE_NODE_OUTPUT, enhanced));
		assertEquals(enhanced.getCanonicalQuery(), StateUtil.getCanonicalQuery(state));
		state.updateState(Map.of(REQUESTED_DENOMINATOR_SCOPE, "ALL"));
		assertTrue(StateUtil.getCanonicalQuery(state).contains("占全部缺陷数量"));
		assertTrue(StateUtil.getCanonicalQuery(state).contains("不因所选大类过滤分母"));
	}
}
