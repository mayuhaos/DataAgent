package com.alibaba.cloud.ai.dataagent.util;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PythonResultValidatorTest {

	private static final String SQL = "SELECT SUM(`p`.`check_total`) AS `production_count` FROM paint_product_shift_stats p";

	@Test
	void rejectsLostAliasFromObservedReportFailure() {
		var error = assertThrows(IllegalStateException.class, () -> PythonResultValidator.validateAliasedSums(SQL,
				List.of(Map.of("production_count", "111192.0000", "first_pass_count", "60803.0000")),
				"{\"production_count\":111192,\"check_total\":null,\"first_pass_rate\":null}"));
		assertTrue(error.getMessage().contains("实际返回字段production_count"));
	}

	@Test
	void acceptsCorrectAliasCalculation() {
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(SQL,
				List.of(Map.of("production_count", "111192.0000")),
				"{\"production_count\":111192,\"check_total\":111192,\"first_pass_rate\":0.5468289085545722}"));
	}

	@Test
	void doesNotGuessAliasFromFieldName() {
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(
				"SELECT SUM(production_count) AS production_count FROM other_table",
				List.of(Map.of("production_count", "111192")),
				"{\"production_count\":111192,\"check_total\":null}"));
	}

	@Test
	void preservesRealNullAndDistinctReturnedSourceField() {
		var row = new java.util.HashMap<String, String>();
		row.put("production_count", null);
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(SQL, List.of(row),
				"{\"production_count\":0,\"check_total\":null}"));
		row.put("production_count", "10");
		row.put("check_total", null);
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(SQL, List.of(row),
				"{\"production_count\":10,\"check_total\":null}"));
	}

	@Test
	void retainsGroupedResultsAndUnrelatedOutput() {
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(SQL,
				List.of(Map.of("production_count", "10"), Map.of("production_count", "20")),
				"{\"production_count\":10,\"check_total\":null}"));
		assertDoesNotThrow(() -> PythonResultValidator.validateAliasedSums(SQL,
				List.of(Map.of("production_count", "10")), "not JSON"));
	}
}
