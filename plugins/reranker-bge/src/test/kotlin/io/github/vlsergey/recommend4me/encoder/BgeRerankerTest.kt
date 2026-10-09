package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class BgeRerankerTest {

    private val reranker = BgeReranker(Onnx(defaultModelsDir()))

    @Test
    fun `a description of the same story scores above one of another, in other words and another language`() {
        assumeTrue(reranker.ready(), "bge-reranker-v2-m3 files are not in the models folder")
        val liked = "Попаданец оказывается в академии магии, где его считают бездарем, и шаг за шагом становится сильнейшим учеником."
        val scores = reranker.score(
            listOf(
                liked to "A boy reborn into a world of sorcery is mocked at the mage school as talentless, then trains until he surpasses everyone.",
                liked to "Уютная история о пекарне в маленьком городке: рецепты, соседи и неспешная любовь.",
                liked to "Космический флот ведёт войну с роем инопланетян; адмирал собирает экипаж из штрафников.",
            ),
        )
        assertTrue(scores[0] > scores[1] && scores[0] > scores[2], "the same story ${scores[0]}, others ${scores[1]}, ${scores[2]}")
    }

    @Test
    fun `a long text is read in its first part and still scores`() {
        assumeTrue(reranker.ready(), "bge-reranker-v2-m3 files are not in the models folder")
        val long = "Герой путешествует по пустыне в поисках древнего города. ".repeat(200)
        val scores = reranker.score(listOf("Приключения в пустыне" to long, "Приключения в пустыне" to "Рецепт борща"))
        assertTrue(scores[0] > scores[1], "${scores[0]} vs ${scores[1]}")
    }
}
