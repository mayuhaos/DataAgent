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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.dataagent.dto.prompt.FeasibilityAssessmentOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CLARIFICATION_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

@Slf4j
public class FeasibilityAssessmentDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		if (state.value(FEASIBILITY_ASSESSMENT_NODE_OUTPUT).isEmpty()) {
			log.warn("Feasibility assessment result is missing, returning END");
			return END;
		}
		FeasibilityAssessmentOutputDTO result = StateUtil.getObjectValue(state, FEASIBILITY_ASSESSMENT_NODE_OUTPUT,
				FeasibilityAssessmentOutputDTO.class);

		if (result == null) {
			log.warn("Feasibility assessment result could not be parsed, returning END");
			return END;
		}

		return switch (result.getRequirementType()) {
			case DATA_ANALYSIS -> {
				log.info("[FeasibilityAssessmentNodeDispatcher]需求类型为数据分析，进入PlannerNode节点");
				yield PLANNER_NODE;
			}
			case NEED_CLARIFICATION -> {
				log.info("[FeasibilityAssessmentNodeDispatcher]需求需要澄清，进入ClarificationNode节点");
				yield CLARIFICATION_NODE;
			}
			case FREE_CHAT -> {
				log.info("[FeasibilityAssessmentNodeDispatcher]需求类型为自由闲聊，返回END节点");
				yield END;
			}
		};
	}

}
