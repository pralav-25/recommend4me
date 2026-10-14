package io.github.vlsergey.recommend4me.refusal

import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** A refusal reaches the interface as a problem by the key of its words and its values, never as words. */
class RefusalAnswersTest {

    @Test
    fun `a refusal is answered with its status, its key and its values`() {
        val refused = Refusal.NO_SETTING.refuse(HttpStatus.BAD_REQUEST, "The source x has no setting 'y'", "setting" to "y")
        val answer = RefusalAnswers().refused(refused)
        assertEquals(400, answer.statusCode.value())
        val problem = answer.body!!
        assertEquals(400, problem.status)
        assertEquals("The source x has no setting 'y'", problem.detail)
        assertEquals("standard:error.noSetting", problem.properties!!["code"])
        assertEquals(mapOf("setting" to "y"), problem.properties!!["params"])
    }

    @Test
    fun `a refusal of no values carries empty values`() {
        val problem = RefusalAnswers().refused(Refusal.TAG_SELF.refuse(HttpStatus.BAD_REQUEST, "no")).body!!
        assertEquals("standard:error.tagSelf", problem.properties!!["code"])
        assertEquals(emptyMap<String, String>(), problem.properties!!["params"])
    }
}
