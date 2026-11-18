package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.beans.Introspector
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class ConcurrentLegalChoicesCacheTest {
    @Test
    fun concurrentQueriesPreserveCommandsAndSeatIsolation() {
        val state = LegalChoicesCacheFixture.expansion()
        val first = PlayerId("p1")
        val second = PlayerId("p2")
        val expected = TurnEngine.choices(state, first)
        val executor = Executors.newFixedThreadPool(4)
        val actual =
            try {
                executor
                    .invokeAll(
                        List(16) {
                            Callable { state.legalChoices(first) to state.legalChoices(second) }
                        }
                    )
                    .map { future -> future.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    "Query test workers must exit."
                }
            }

        assertThat(actual.map { it.first }).containsExactlyElementsIn(List(16) { expected })
        assertThat(actual.flatMap { it.second.commands }).isEmpty()
    }

    @Test
    fun derivedCacheIsAbsentFromPublicSerializationProperties() {
        val properties = Introspector.getBeanInfo(GameState::class.java).propertyDescriptors

        assertThat(properties.map { it.name }.filter { it.contains("choices", ignoreCase = true) })
            .isEmpty()
    }
}
