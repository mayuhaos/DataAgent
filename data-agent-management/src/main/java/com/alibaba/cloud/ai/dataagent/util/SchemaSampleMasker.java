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

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

public final class SchemaSampleMasker {

	private static final int MAX_SAMPLE_LENGTH = 100;

	private SchemaSampleMasker() {
	}

	public static List<String> mask(String columnName, List<String> samples) {
		if (samples == null || samples.isEmpty() || isSecretColumn(columnName)) {
			return List.of();
		}
		return samples.stream()
			.filter(StringUtils::isNotBlank)
			.map(value -> StringUtils.left(value, MAX_SAMPLE_LENGTH))
			.map(value -> maskValue(columnName, value))
			.distinct()
			.limit(3)
			.toList();
	}

	private static String maskValue(String columnName, String value) {
		String normalized = normalize(columnName);
		if (containsAny(normalized, "phone", "mobile", "telephone", "tel", "手机号", "电话")) {
			return keepEdges(value, 3, 4);
		}
		if (containsAny(normalized, "email", "mail", "邮箱")) {
			int separator = value.indexOf('@');
			return separator > 0 ? value.substring(0, 1) + "***" + value.substring(separator) : keepEdges(value, 1, 0);
		}
		if (containsAny(normalized, "idcard", "id_card", "identity", "证件", "身份证", "bankcard", "bank_card",
				"银行卡", "account_no", "account_number")) {
			return keepEdges(value, 4, 4);
		}
		if (isNameColumn(normalized) || containsAny(normalized, "address", "地址")) {
			return keepEdges(value, 1, 0);
		}
		return value;
	}

	private static boolean isSecretColumn(String columnName) {
		String normalized = normalize(columnName);
		return containsAny(normalized, "password", "passwd", "pwd", "secret", "token", "api_key", "apikey",
				"private_key", "credential", "密码", "密钥", "令牌");
	}

	private static boolean isNameColumn(String normalized) {
		return normalized.equals("name") || normalized.endsWith("_name") || normalized.equals("姓名")
				|| normalized.endsWith("姓名");
	}

	private static String keepEdges(String value, int prefixLength, int suffixLength) {
		if (value.length() <= prefixLength + suffixLength) {
			return "***";
		}
		String prefix = value.substring(0, Math.min(prefixLength, value.length()));
		String suffix = suffixLength == 0 ? "" : value.substring(value.length() - suffixLength);
		return prefix + "***" + suffix;
	}

	private static String normalize(String value) {
		return StringUtils.defaultString(value).toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
	}

	private static boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) {
			if (value.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

}
