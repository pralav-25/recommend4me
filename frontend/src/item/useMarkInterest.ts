import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ContentTypeInfo, type ItemRef, type ItemSummary } from "@/api/client";
import { toast } from "@/components/ui/toast";
import { findSource } from "@/contenttype/types";
import i18n from "@/i18n/i18n";
import { applyInterest, itemKey } from "./lists";

type Mark = { summary: ItemSummary; interesting: boolean | null };

async function send(ref: ItemRef, interesting: boolean | null): Promise<ItemSummary> {
  const path = { source: ref.source, item: ref.item };
  return interesting === null
    ? unwrap(await api.DELETE("/api/items/{source}/{item}/interest", { params: { path } }))
    : unwrap(await api.PUT("/api/items/{source}/{item}/interest", { params: { path }, body: { interesting } }));
}

export function interestLabel(interesting: boolean | null | undefined): string {
  return i18n.t(interesting === true ? "MarkInterest.interesting" : interesting === false ? "MarkInterest.dull" : "MarkInterest.cleared", { ns: "item" });
}

/**
 * Marking a work interesting to read or not. Every cached list has the card replaced in place —
 * its order is the model's after the next training. A toast offers to undo.
 */
export function useMarkInterest() {
  const queryClient = useQueryClient();

  const apply = (updated: ItemSummary) => {
    applyInterest(queryClient, updated);
    const type = findSource(queryClient.getQueryData<ContentTypeInfo[]>(["types"]), updated.source)?.type;
    queryClient.invalidateQueries({ queryKey: ["model", type?.id] });
    queryClient.invalidateQueries({ queryKey: itemKey(updated) });
  };

  return useMutation({
    mutationFn: ({ summary, interesting }: Mark) => send(summary, interesting),
    onSuccess: (updated, { summary }) => {
      apply(updated);
      const previous = summary.interest ?? null;
      toast.add({
        title: interestLabel(updated.interest),
        description: `${summary.title} ${summary.version}`.trim(),
        timeout: 5000,
        actionProps: {
          children: i18n.t("MarkInterest.undo", { ns: "item" }),
          onClick: async () => apply(await send(summary, previous)),
        },
      });
    },
    onError: (error) => toast.add({ title: i18n.t("MarkInterest.failed", { ns: "item" }), description: error.message, type: "error" }),
  });
}
