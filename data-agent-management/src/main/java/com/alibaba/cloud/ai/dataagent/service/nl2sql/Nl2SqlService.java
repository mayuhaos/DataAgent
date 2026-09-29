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
package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SemanticConsistencyDTO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface Nl2SqlService {

	Pattern SQL_START_PATTERN = Pattern.compile("(?i)(?<![\\w$])(with|select)\\b");

	Pattern SELECT_MISSING_LIST_PATTERN = Pattern.compile("(?is)^\\s*select\\s+from\\b");

	Flux<ChatResponse> performSemanticConsistency(SemanticConsistencyDTO semanticConsistencyDTO);

	Flux<String> generateSql(SqlGenerationDTO sqlGenerationDTO);

	Flux<ChatResponse> fineSelect(SchemaDTO schemaDTO, String query, String evidence,
			String sqlGenerateSchemaMissingAdvice, DbConfigBO specificDbConfig, Consumer<SchemaDTO> dtoConsumer);

	default String sqlTrim(String sql) {
		return sqlTrim(sql, "mysql");
	}

	/**
	 * Extract one read-only SQL statement from an LLM response and validate it with
	 * Druid's dialect-aware parser. Returning an empty string makes the caller treat the
	 * output as a generation failure rather than submitting prose to a datasource.
	 */
	default String sqlTrim(String sql, String dialect) {
		String content = MarkdownParserUtil.extractRawText(sql).trim();
		DbType dbType = resolveDbType(dialect);
		Matcher matcher = SQL_START_PATTERN.matcher(content);
		while (matcher.find()) {
			String candidate = firstSqlStatement(content.substring(matcher.start()));
			if (SELECT_MISSING_LIST_PATTERN.matcher(candidate).find()) {
				continue;
			}
			try {
				SQLStatement statement = SQLUtils.parseSingleStatement(candidate, dbType);
				if (statement instanceof SQLSelectStatement) {
					return candidate.trim();
				}
			}
			catch (RuntimeException ignored) {
				// Try the next SELECT/WITH occurrence; the caller handles a final empty result.
			}
		}
		return "";
	}

	private static DbType resolveDbType(String dialect) {
		DbType dbType = DbType.of(dialect);
		return dbType != null ? dbType : DbType.mysql;
	}

	private static String firstSqlStatement(String candidate) {
		boolean singleQuoted = false;
		boolean doubleQuoted = false;
		boolean backtickQuoted = false;
		for (int index = 0; index < candidate.length(); index++) {
			char current = candidate.charAt(index);
			if (current == '\'' && !doubleQuoted && !backtickQuoted) {
				if (singleQuoted && index + 1 < candidate.length() && candidate.charAt(index + 1) == '\'') {
					index++;
					continue;
				}
				singleQuoted = !singleQuoted;
			}
			else if (current == '"' && !singleQuoted && !backtickQuoted) {
				doubleQuoted = !doubleQuoted;
			}
			else if (current == '`' && !singleQuoted && !doubleQuoted) {
				backtickQuoted = !backtickQuoted;
			}
			else if (current == ';' && !singleQuoted && !doubleQuoted && !backtickQuoted) {
				return candidate.substring(0, index + 1);
			}
		}
		return candidate;
	}

}
