package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GameNameTest {
    @Test
    fun parsingTrimsSurroundingWhitespaceAndPreservesTheCreatorsSpelling() {
        val name = GameName.parse("  Les marchands de Gênes  ")

        assertThat(name.value).isEqualTo("Les marchands de Gênes")
    }

    @Test
    fun namesCannotBeBlankMultilineOrLongerThanEightyCharacters() {
        listOf("", " ", "First\nSecond", "First\tSecond", "A".repeat(81)).forEach { value ->
            assertFailsWith<IllegalArgumentException> { GameName.parse(value) }
        }
    }

    @Test
    fun namesSerializeAsPlainText() {
        val name = GameName("Merchants & explorers")

        assertThat(json.readTree(json.writeValueAsString(name)).asText()).isEqualTo(name.value)
    }
}
