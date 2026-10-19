package dev.obiente.nextcloudnative.contracts

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Structural equality of org.json values, written only with members that Android's own org.json
 * also provides (`keys`, `length`, `has`, `opt`), because Android loads its framework copy ahead
 * of the library this module compiles against. JSON-java's `JSONObject.similar` is not available
 * there.
 *
 * Objects are equal when they have the same keys, in any order, with equivalent values; arrays
 * when they have equivalent values in the same order. Numbers are compared by value, so `1` and
 * `1.0` are equal: both are the same JSON number, and parsers may read them as different types.
 * An explicit JSON null is not equal to a missing key.
 */
internal fun jsonEquivalent(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> left.length() == right.length() &&
        left.keys().asSequence().all { key -> right.has(key) && jsonEquivalent(left.opt(key), right.opt(key)) }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { index -> jsonEquivalent(left.opt(index), right.opt(index)) }
    left is Number && right is Number -> {
        val leftValue = numericValue(left)
        val rightValue = numericValue(right)
        leftValue != null && rightValue != null && leftValue.compareTo(rightValue) == 0
    }
    else -> left == right
}

private fun numericValue(number: Number): BigDecimal? = number.toString().toBigDecimalOrNull()
