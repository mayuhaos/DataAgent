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
package com.alibaba.cloud.ai.dataagent.util;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MarkdownParserUtil {

	private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^\\s*:?-{3,}:?\\s*$");

	private static final Pattern FENCED_CODE_LINE = Pattern.compile("^\\s*(`{3,}|~{3,})");


	public static String extractText(String markdownCode) {
		String code = extractRawText(markdownCode);
		// Correctly handle various newline character types: \r\n, \n, \r, but maintain
		// compatibility with NewLineParser.format()
		return code.replaceAll("\r\n", " ").replaceAll("\n", " ").replaceAll("\r", " ");
	}

	public static String extractRawText(String markdownCode) {
		// Find the start of a code block (3 or more backticks)
		int startIndex = -1;
		int delimiterLength = 0;

		for (int i = 0; i <= markdownCode.length() - 3; i++) {
			if (markdownCode.substring(i, i + 3).equals("```")) {
				startIndex = i;
				delimiterLength = 3;
				// Count additional backticks
				while (i + delimiterLength < markdownCode.length() && markdownCode.charAt(i + delimiterLength) == '`') {
					delimiterLength++;
				}
				break;
			}
		}

		if (startIndex == -1) {
			return markdownCode; // No code block found
		}

		// Skip the opening delimiter and optional language specification
		int contentStart = startIndex + delimiterLength;
		while (contentStart < markdownCode.length() && markdownCode.charAt(contentStart) != '\n') {
			contentStart++;
		}
		if (contentStart < markdownCode.length() && markdownCode.charAt(contentStart) == '\n') {
			contentStart++; // Skip the newline after language spec
		}

		// Find the closing delimiter
		String closingDelimiter = "`".repeat(delimiterLength);
		int endIndex = markdownCode.indexOf(closingDelimiter, contentStart);

		if (endIndex == -1) {
			// No closing delimiter found, return from content start to end
			return markdownCode.substring(contentStart);
		}

		// Extract just the content between delimiters
		return markdownCode.substring(contentStart, endIndex);
	}

	/**
	 * Repairs a common malformed-table shape without depending on a specific heading
	 * convention. A line whose suffix is a table header and whose next line is a table
	 * separator is split into a preceding text block and a valid table header.
	 */
	public static String normalizeReportMarkdown(String markdown) {
		if (markdown == null || markdown.isEmpty()) {
			return markdown;
		}

		String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		StringBuilder normalized = new StringBuilder(markdown.length() + 16);
		boolean inFence = false;
		char fenceMarker = 0;

		for (int index = 0; index < lines.length; index++) {
			String line = lines[index];
			Matcher fence = FENCED_CODE_LINE.matcher(line);
			if (fence.find()) {
				char marker = fence.group(1).charAt(0);
				if (!inFence) {
					inFence = true;
					fenceMarker = marker;
				}
				else if (marker == fenceMarker) {
					inFence = false;
					fenceMarker = 0;
				}
			}

			if (!inFence && index + 1 < lines.length) {
				int firstPipe = line.indexOf('|');
				if (firstPipe > 0 && isTableSeparator(lines[index + 1])) {
					String prefix = normalizeAtxHeading(line.substring(0, firstPipe).stripTrailing());
					if (!prefix.isBlank()) {
						appendLine(normalized, prefix);
						appendLine(normalized, "");
						appendLine(normalized, line.substring(firstPipe).trim());
						continue;
					}
				}
			}

			appendLine(normalized, line);
		}

		return normalized.toString();
	}

	private static boolean isTableSeparator(String line) {
		String stripped = line.trim();
		if (stripped.startsWith("|")) {
			stripped = stripped.substring(1);
		}
		if (stripped.endsWith("|")) {
			stripped = stripped.substring(0, stripped.length() - 1);
		}
		String[] cells = stripped.split("\\|", -1);
		return cells.length > 0 && java.util.Arrays.stream(cells).allMatch(cell -> TABLE_SEPARATOR_CELL.matcher(cell).matches());
	}

	private static String normalizeAtxHeading(String line) {
		return line.replaceFirst("^(\\s*#{1,6})(?!#)(\\S)", "$1 $2");
	}

	private static void appendLine(StringBuilder builder, String line) {
		if (!builder.isEmpty()) {
			builder.append('\n');
		}
		builder.append(line);
	}

}
