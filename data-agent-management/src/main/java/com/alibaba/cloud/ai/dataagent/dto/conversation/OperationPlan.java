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
package com.alibaba.cloud.ai.dataagent.dto.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** Structured, non-executable plan returned by the conversation planner. */
@Data
public class OperationPlan {

	public enum Operation { CREATE, DERIVE, TRANSFORM, REVISE, EXPLAIN, COMPARE, CONTINUE, SWITCH_TOPIC, ASK_CLARIFICATION }

	public enum ExecutionMode { NO_EXECUTION, REUSE, TRANSFORM_LOCAL, REGENERATE_REPORT, REQUERY, ASK_CLARIFICATION }

	private Operation operation = Operation.CONTINUE;

	private Target target = new Target();

	private Changes changes = new Changes();

	private ExecutionMode executionMode = ExecutionMode.ASK_CLARIFICATION;

	private double confidence;

	private String clarificationQuestion;

	private String reason;

	@Data
	public static class Target {
		private List<String> topicIds = new ArrayList<>();
		private List<String> artifactIds = new ArrayList<>();
	}

	@Data
	public static class Changes {
		private DataChanges data = new DataChanges();
		private PresentationChanges presentation = new PresentationChanges();
		private ReportChanges report = new ReportChanges();
	}

	@Data
	public static class DataChanges {
		private List<String> metrics = new ArrayList<>();
		private List<String> dimensions = new ArrayList<>();
		private Map<String, Object> filters;
		private Object timeRange;
		private String grain;
		private String sort;
		private Integer limit;
	}

	@Data
	public static class PresentationChanges {
		private String chartType;
		private String x;
		private List<String> y = new ArrayList<>();
		private String title;
		private List<String> visibleFields = new ArrayList<>();
	}

	@Data
	public static class ReportChanges {
		private String style;
		private List<String> sections = new ArrayList<>();
	}

}
