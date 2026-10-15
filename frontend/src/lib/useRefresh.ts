import { usePref } from "./usePref";

/** How often an area may read its data again ([RefreshSelect]); 0 — only when asked. */
export const REFRESH_EVERY = [0, 1_000, 2_000, 5_000, 10_000, 30_000, 60_000];

/**
 * HOW OFTEN AN AREA READS ITS DATA AGAIN BY ITSELF, as this browser chose for that area: the choice
 * is kept under the area's own [key] (localStorage, [usePref]), and until one is made — or when the
 * one kept is no longer offered — the area reads as often as [fallback] says. Handed to a query as
 * its `refetchInterval` (`every || false`): the next read is not started while one is under way.
 */
export function useRefresh(key: string, fallback: number): [number, (ms: number) => void] {
  const [kept, setEvery] = usePref<number>(key, fallback);
  return [REFRESH_EVERY.includes(kept) ? kept : fallback, setEvery];
}
