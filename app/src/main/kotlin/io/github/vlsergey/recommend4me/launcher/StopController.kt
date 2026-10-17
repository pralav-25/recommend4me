package io.github.vlsergey.recommend4me.launcher

import io.github.vlsergey.recommend4me.api.LauncherApi
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger(StopController::class.java)

/**
 * THE APPLICATION STOPPED AS IT SHOULD BE, by the user's command (2026-10-17): the context closed —
 * every database closed, its file whole; the executors of the background stopped — and the process
 * ended, the window it runs in closed with it. Never killed: a database killed in the middle of its
 * writing, its compaction, was lost (2026-10-16). The request is answered first; the stop is a thread
 * of its own, not the request's.
 */
@RestController
class StopController(private val context: ConfigurableApplicationContext) : LauncherApi {

    override fun stopApplication(): ResponseEntity<Unit> {
        log.info("Stopping, as asked")
        Thread({
            // The answer sent before the server stops
            Thread.sleep(ANSWERED_MILLIS)
            exitProcess(SpringApplication.exit(context))
        }, "stop").start()
        return ResponseEntity.accepted().build()
    }

    private companion object {
        /** Long enough for the answer to leave. */
        const val ANSWERED_MILLIS = 500L
    }
}
