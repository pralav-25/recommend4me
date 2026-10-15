package io.github.vlsergey.recommend4me.workload

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * [body] run on a thread of its own, as the background runs it, and waited for: the thread of a
 * test stands for a request of the browser (Spring binds a request to it), and a request never
 * waits for a computation ([Requests]). For a test standing for the background — the vectors made,
 * the tags named — where the application does it after the quiet.
 */
fun <T> inBackground(body: () -> T): T = try {
    CompletableFuture.supplyAsync(body, { Thread(it, "test-background").start() }).get()
} catch (e: ExecutionException) {
    throw e.cause ?: e
}
