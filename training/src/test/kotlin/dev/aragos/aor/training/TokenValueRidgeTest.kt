package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenValueRidgeTest {
    @Test
    fun fitsPairedDifferencesWithoutAnIntercept() {
        val examples =
            listOf(TokenValueExample(listOf(1.0), 0.5), TokenValueExample(listOf(-1.0), -0.5))

        val coefficients = TokenValueRidge.fit(examples, regularization = 0.1)

        assertThat(coefficients.single()).isWithin(1.0e-12).of(0.5 / 1.1)
        assertThat(TokenValueRidge.predict(coefficients, listOf(0.0))).isEqualTo(0.0)
    }

    @Test
    fun positiveRidgeHandlesExactlyCollinearFeatures() {
        val coefficients =
            TokenValueRidge.fit(
                listOf(TokenValueExample(listOf(1.0, 1.0), 1.0)),
                regularization = 1.0,
            )

        assertThat(coefficients[0]).isWithin(1.0e-12).of(1.0 / 3.0)
        assertThat(coefficients[1]).isWithin(1.0e-12).of(1.0 / 3.0)
    }

    @Test
    fun moreAlternativesDoNotGiveOneOriginMoreWeight() {
        val onePerOrigin =
            listOf(TokenValueExample(listOf(1.0), 1.0), TokenValueExample(listOf(1.0), 0.0))
        val moreSecondOrigin =
            listOf(TokenValueExample(listOf(1.0), 1.0)) +
                List(10) { TokenValueExample(listOf(1.0), 0.0, weight = 0.1) }

        val first = TokenValueRidge.fit(onePerOrigin, 1.0)
        val second = TokenValueRidge.fit(moreSecondOrigin, 1.0)

        assertThat(first.single()).isWithin(1.0e-12).of(0.25)
        assertThat(second.single()).isWithin(1.0e-12).of(first.single())
    }

    @Test
    fun allZeroTargetsRemainAnAbstainingHead() {
        val coefficients =
            TokenValueRidge.fit(
                listOf(TokenValueExample(listOf(1.0, -1.0), 0.0)),
                0.01,
            )

        assertThat(coefficients).containsExactly(0.0, 0.0)
    }

    @Test
    fun expiredDeadlineStopsFitting() {
        assertFailsWith<CancellationException> {
            TokenValueRidge.fit(
                listOf(TokenValueExample(listOf(1.0), 0.0)),
                0.01,
                System.nanoTime() - 1,
            )
        }
    }
}
