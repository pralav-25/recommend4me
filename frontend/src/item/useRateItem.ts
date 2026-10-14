import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ContentTypeInfo, type Grade, type ItemRef, type ItemSummary, type RatingRecord } from "@/api/client";
import { toast } from "@/components/ui/toast";
import { findSource, gradeLabel } from "@/contenttype/types";
import i18n from "@/i18n/i18n";
import { applyGrade, itemKey, ITEMS } from "./lists";
import { heldOutLater } from "./useHeldOut";

type Rate = { summary: ItemSummary; grade: Grade | null };

async function send(ref: ItemRef, grade: Grade | null): Promise<ItemSummary> {
  const path = { source: ref.source, item: ref.item };
  return grade === null
    ? unwrap(await api.DELETE("/api/items/{source}/{item}/rating", { params: { path } }))
    : unwrap(await api.PUT("/api/items/{source}/{item}/rating", { params: { path }, body: { grade } }));
}

/**
 * Grading an item. Every cached list is updated in place: an item graded in the "unrated" view
 * leaves it at once, so the next one moves up under the cursor. A toast offers to undo.
 */
export function useRateItem() {
  const queryClient = useQueryClient();

  const apply = (updated: ItemSummary) => {
    applyGrade(queryClient, updated);
    const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), updated.source)?.type;
    queryClient.invalidateQueries({ queryKey: ["model", type?.id] });
    queryClient.invalidateQueries({ queryKey: itemKey(updated) });
    queryClient.invalidateQueries({ queryKey: ["history"] });
    heldOutLater(queryClient, updated);
  };

  return useMutation({
    mutationFn: ({ summary, grade }: Rate) => send(summary, grade),
    onSuccess: (updated, { summary }) => {
      apply(updated);
      const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), updated.source)?.type;
      const previous = (summary.grade as Grade | undefined) ?? null;
      toast.add({
        title: updated.grade ? (type ? gradeLabel(type.id, updated.grade as Grade) : String(updated.grade)) : i18n.t("RateItem.cleared", { ns: "item" }),
        description: `${summary.title} ${summary.version}`.trim(),
        timeout: 5000,
        actionProps: {
          children: i18n.t("RateItem.undo", { ns: "item" }),
          onClick: async () => {
            apply(await send(summary, previous));
            // An item put back into a list it had left needs the list from the server
            queryClient.invalidateQueries({ queryKey: [ITEMS] });
          },
        },
      });
    },
    onError: (error) => toast.add({ title: i18n.t("RateItem.failed", { ns: "item" }), description: error.message, type: "error" }),
  });
}

/**
 * Takes back one grade of the work's history: of an earlier version, or of another item of the
 * work. The shown item is read anew — its card's grade may come from the grade taken back — and
 * its card is updated in the lists as a new grade of it would be.
 */
export function useTakeBackGrade(shown: ItemRef) {
  const queryClient = useQueryClient();
  return async (record: RatingRecord) => {
    unwrap(
      await api.DELETE("/api/items/{source}/{item}/rating", {
        params: { path: { source: record.source, item: record.item }, query: { version: record.version || undefined } },
      }),
    );
    const details = unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: { source: shown.source, item: shown.item } } }));
    queryClient.setQueryData(itemKey(shown), details);
    applyGrade(queryClient, details.summary);
    heldOutLater(queryClient, details.summary);
    const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), shown.source)?.type;
    queryClient.invalidateQueries({ queryKey: ["model", type?.id] });
    queryClient.invalidateQueries({ queryKey: ["history"] });
  };
}
