package com.alibaba.cloud.ai.dataagent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Keeps factual blocks out of a style-only model rewrite. */
public final class ReportFactsProtector {

	private static final Pattern NUMBER = Pattern.compile("\\p{N}|[零〇一二三四五六七八九十百千万亿两]+(?:个|件|条|次|点|成|%)");

	private ReportFactsProtector() { }

	public static ProtectedReport protect(String report) {
		String normalized = report.replace("\r\n", "\n").replace('\r', '\n');
		var offsets = new ArrayList<Integer>();
		offsets.add(0);
		for (int i = 0; i < normalized.length(); i++) if (normalized.charAt(i) == '\n') offsets.add(i + 1);
		var document = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(normalized);
		Map<String, String> facts = new LinkedHashMap<>();
		StringBuilder editable = new StringBuilder();
		int cursor = 0;
		for (var block = document.getFirstChild(); block != null; block = block.getNext()) {
			var spans = block.getSourceSpans();
			if (spans.isEmpty()) continue;
			var first = spans.get(0);
			var last = spans.get(spans.size() - 1);
			int start = offsets.get(first.getLineIndex());
			int end = offsets.get(last.getLineIndex()) + last.getColumnIndex() + last.getLength();
			String content = normalized.substring(start, end);
			if (block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock
					|| content.contains("|") || NUMBER.matcher(content).find()) {
				String marker = "DATA_AGENT_FACT_" + UUID.randomUUID().toString().replace("-", "");
				facts.put(marker, content);
				editable.append(normalized, cursor, start).append(marker);
				cursor = end;
			}
		}
		editable.append(normalized.substring(cursor));
		return new ProtectedReport(editable.toString(), facts);
	}

	public record ProtectedReport(String editable, Map<String, String> facts) {

		public String restore(String revised) {
			String normalized = revised.replace("\r\n", "\n").replace('\r', '\n');
			String prose = normalized;
			for (String marker : facts.keySet()) {
				if (normalized.indexOf(marker) < 0 || normalized.indexOf(marker) != normalized.lastIndexOf(marker)
						|| !Pattern.compile("(?m)^" + Pattern.quote(marker) + "$", Pattern.MULTILINE).matcher(normalized).find()) {
					throw new FactChangeException("报告改写遗漏或重复了原始数据块，已保留原报告，请重试。");
				}
				prose = prose.replace(marker, "");
			}
			if (NUMBER.matcher(prose).find() || prose.contains("```") || prose.contains("~~~") || prose.contains("|")) {
				throw new FactChangeException("报告改写新增了未经核验的数值、表格或图表，已保留原报告，请重试。");
			}
			for (var entry : facts.entrySet()) normalized = normalized.replace(entry.getKey(), entry.getValue());
			return normalized;
		}
	}

	public static class FactChangeException extends IllegalStateException {
		public FactChangeException(String message) { super(message); }
	}
}
