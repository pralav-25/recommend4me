package io.github.vlsergey.recommend4me.workload

import org.springframework.web.context.request.RequestContextHolder

/**
 * A REQUEST OF THE BROWSER NEVER WAITS FOR A COMPUTATION, NOR RUNS ONE: by the user's word
 * (2026-10-16), the marking and the pages answer at once. A request writes what it was told and
 * plans the works that follow ([Workload.plan]); it reads what the works have made so far. The
 * thread serving a request is the one Spring's dispatcher bound the request's attributes to, for
 * the request's time (they are not passed to the threads it starts). A wait asked for on it is a
 * mistake of the code, not of the user: it fails at once, in the tests as on the user's machine,
 * instead of hanging the page while a training runs.
 */
object Requests {
    /** Whether the current thread serves a request of the browser. */
    fun serving(): Boolean = RequestContextHolder.getRequestAttributes() != null

    /** Fails when the current thread serves a request: [what] — a computation, a wait — is not to be done on it. */
    fun refuseWaiting(what: Any) = check(!serving()) { "$what asked for on the thread of a request of the browser: a request never waits for a computation — it plans the work and answers with what there is" }
}
