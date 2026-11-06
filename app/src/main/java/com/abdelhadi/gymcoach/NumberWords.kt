package com.abdelhadi.gymcoach

/** English number words, so the English voice always counts in English. */
object NumberWords {
    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun en(n: Int): String = when {
        n < 20 -> ones[n.coerceAtLeast(0)]
        n < 100 -> tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else ""
        n == 100 -> "one hundred"
        else -> n.toString()
    }
}
