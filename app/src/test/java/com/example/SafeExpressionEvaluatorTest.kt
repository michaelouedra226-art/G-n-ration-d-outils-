package com.example

import com.example.core.security.SafeExpressionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeExpressionEvaluatorTest {

    @Test
    fun `evaluates arithmetic and operator precedence`() {
        val result = SafeExpressionEvaluator.evaluate("2 + 3 * 4 ^ 2 - (10 / 2)")
        assertEquals(45.0, result, 1e-6)
    }

    @Test
    fun `evaluates variables and whitelisted math functions`() {
        val bmi = SafeExpressionEvaluator.evaluate(
            expression = "round((weight / pow(height / 100, 2)) * 10) / 10",
            variables = mapOf("weight" to 72.0, "height" to 180.0)
        )
        assertEquals(22.2, bmi, 1e-6)
    }

    @Test
    fun `evaluates conditional if comparison`() {
        val discounted = SafeExpressionEvaluator.evaluate(
            expression = "if(total >= 100, total * 0.9, total)",
            variables = mapOf("total" to 120.0)
        )
        assertEquals(108.0, discounted, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects division by zero`() {
        SafeExpressionEvaluator.evaluate("42 / 0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown functions or arbitrary code`() {
        SafeExpressionEvaluator.evaluate("exec(1)")
    }

    @Test
    fun `validateSyntax rejects malformed expression`() {
        val res = SafeExpressionEvaluator.validateSyntax("2 + * 3")
        assertTrue(res.isFailure)
    }
}
