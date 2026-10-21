import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, failure, unwrap, type JobStatus, type SourceMode } from "@/api/client";
import { useRefresh } from "@/lib/useRefresh";

export const ACTIVE = new Set<JobStatus["state"]>(["RUNNING", "CANCELLING"]);

export function isActive(job: JobStatus | undefined): boolean {
  return job !== undefined && ACTIVE.has(job.state);
}

/**
 * The load of the whole catalogue was started and has not come to the end: it can be continued,
 * across runs of the job and restarts of the application.
 */
export function catalogueUnfinished(job: JobStatus | undefined): boolean {
  const c = job?.catalogue;
  return c ? !c.complete && c.stage !== "none" : job?.resumable === "SCRAPE_ALL";
}

/** How often the jobs are asked for again while one runs, till the user chooses ([useJobsRefresh]). */
const EVERY_MS = 1000;

/** Of the jobs none running: asked for again no oftener than this — a job is started here, or by a schedule. */
const IDLE_MS = 30_000;

/** How often the jobs are read again, as the user chose in the dialog of a job ([RefreshSelect]). */
export function useJobsRefresh(): [number, (ms: number) => void] {
  return useRefresh("jobs.refresh", EVERY_MS);
}

/** The jobs of every scraping source: as often as chosen while one runs, otherwise now and then; never when "off". */
export function useJobs() {
  const [every] = useJobsRefresh();
  return useQuery({
    queryKey: ["jobs"],
    queryFn: async () => unwrap(await api.GET("/api/jobs")),
    refetchInterval: (q) => (every ? (q.state.data?.some(isActive) ? every : Math.max(every, IDLE_MS)) : false),
  });
}

function putJob(jobs: JobStatus[] | undefined, job: JobStatus): JobStatus[] {
  return [...(jobs ?? []).filter((j) => j.source !== job.source), job];
}

export function useStartJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ source, mode }: { source: string; mode: SourceMode }) => {
      const result = await api.POST("/api/sources/{source}/job", { params: { path: { source } }, body: { mode } });
      // 409: one runs already, or the source has no such mode — the answer is the job as it is
      if (result.data) return result.data;
      const job = result.error as Partial<JobStatus> | undefined;
      if (result.response.status === 409 && job?.state) return job as JobStatus;
      throw failure(result);
    },
    onSuccess: (job) => queryClient.setQueryData<JobStatus[]>(["jobs"], (jobs) => putJob(jobs, job)),
  });
}

export function useCancelJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (source: string) => unwrap(await api.DELETE("/api/sources/{source}/job", { params: { path: { source } } })),
    onSuccess: (job) => queryClient.setQueryData<JobStatus[]>(["jobs"], (jobs) => putJob(jobs, job)),
  });
}
