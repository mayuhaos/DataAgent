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
package com.alibaba.cloud.ai.dataagent.workflow;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identifies chart-edit requests that end before the editable property or its value is
 * provided. This must run before query enhancement because that node can otherwise
 * borrow a complete question from the conversation history.
 */
public final class IncompleteResultEditDetector {

	private static final Pattern INCOMPLETE_AXIS_TAIL = Pattern.compile(".*(?:[xy]轴|横轴|纵轴|坐标轴|[xy])\\s*$");

	private static final Pattern INCOMPLETE_CHART_POSSESSIVE_TAIL = Pattern
		.compile(".*(?:图表?\\s*\\d*|这张图|上面这张图)的\\s*$");

	private IncompleteResultEditDetector() {
	}

	public static boolean isIncomplete(String input) {
		if (StringUtils.isBlank(input)) {
			return false;
		}
		int lastSupplement = input.lastIndexOf("用户补充");
		if (lastSupplement >= 0) {
			String supplement = input.substring(lastSupplement).replaceFirst("^用户补充\\d*：", "").strip();
			int lineBreak = supplement.indexOf('\n');
			return isIncompleteFollowUp(lineBreak >= 0 ? supplement.substring(0, lineBreak) : supplement);
		}
		return isIncompleteDirectEdit(input);
	}

	private static boolean isIncompleteDirectEdit(String input) {

		String normalized = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		boolean refersToChart = normalized.contains("图") || normalized.contains("chart") || normalized.contains("x轴")
				|| normalized.contains("y轴") || normalized.contains("横轴") || normalized.contains("纵轴")
				|| normalized.contains("坐标轴");
		boolean isEditRequest = normalized.matches(".*(?:把|改|修改|调整|设置|设为|显示|隐藏|保留|删除|变更).*");
		if (!refersToChart || !isEditRequest) {
			return false;
		}

		return INCOMPLETE_AXIS_TAIL.matcher(normalized).matches()
				|| INCOMPLETE_CHART_POSSESSIVE_TAIL.matcher(normalized).matches();
	}

	private static boolean isIncompleteFollowUp(String input) {
		if (StringUtils.isBlank(input)) {
			return true;
		}
		String normalized = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		return INCOMPLETE_AXIS_TAIL.matcher(normalized).matches()
				|| INCOMPLETE_CHART_POSSESSIVE_TAIL.matcher(normalized).matches();
	}

	public static String clarificationQuestion() {
		return "请说明要修改图表的哪个属性。例如，若要修改 Y 轴，请补充上限、下限、标题或刻度范围；涉及范围时请提供具体数值。";
	}

}
