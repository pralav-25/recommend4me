import { CheckIcon, ThumbsDownIcon, ThumbsUpIcon, XIcon } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type ItemRef } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { toast } from "@/components/ui/toast";
import { itemKey } from "@/item/lists";
import { cn } from "@/lib/utils";

/**
 * The two marks, "for" and "against"; pressing the chosen one again clears it. While the mark is
 * being saved, its icon spins; a failure is shown on the button.
 */
export function MarkButtons({
  mark,
  onMark,
  className,
}: {
  mark?: number;
  onMark: (value: number | null) => Promise<unknown>;
  className?: string;
}) {
  const { t } = useTranslation("mark");
  const button = (value: number, title: string, Icon: typeof ThumbsUpIcon, on: string) => (
    <AsyncButton
      variant="ghost"
      size="icon-xs"
      title={mark === value ? t("MarkButtons.clear") : title}
      onClick={() => onMark(mark === value ? null : value)}
      icon={<Icon className="size-3.5" />}
      className={cn("bg-black/60 text-white hover:bg-black/80 hover:text-white", mark === value && on)}
    />
  );
  return (
    <span className={cn("flex gap-1", className)}>
      {button(1, t("MarkButtons.for"), ThumbsUpIcon, "bg-yes! hover:bg-yes/90!")}
      {button(-1, t("MarkButtons.against"), ThumbsDownIcon, "bg-no! hover:bg-no/90!")}
    </span>
  );
}

/**
 * The user's word on a match: ✓ alike in the sense meant, ✕ alike in another sense. Pressing the
 * chosen one again takes it back; a "not that" can also be taken back from its toast, since the
 * match leaves the dialog once the likeness is made again.
 */
export function MatchVerdict({
  item,
  kind,
  mark,
  markRef,
  matchRef,
  verdict,
}: {
  /** The item whose dialog shows the match: the match is one of its pictures or reviews. */
  item: ItemRef;
  kind: "PICTURE" | "REVIEW";
  /** The item of the marked picture or review. */
  mark: ItemRef;
  markRef: string;
  matchRef: string;
  verdict?: number;
}) {
  const { t } = useTranslation("mark");
  const queryClient = useQueryClient();
  const send = async (value: number) => {
    ensureOk(
      await api.POST("/api/match-feedback", {
        body: {
          kind,
          markSource: mark.source,
          markItem: mark.item,
          markRef,
          matchSource: item.source,
          matchItem: item.item,
          matchRef,
          verdict: value,
        },
      }),
    );
    await queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  const give = async (value: number) => {
    await send(value);
    if (value !== -1) return;
    toast.add({
      title: t("MatchVerdict.otherSense"),
      description: t("MatchVerdict.recountNote"),
      timeout: 5000,
      actionProps: { children: t("MatchVerdict.undo"), onClick: () => send(0) },
    });
  };
  // Big enough for a finger: a tablet has no pointer to aim with
  const button = (value: number, title: string, Icon: typeof CheckIcon, on: string) => (
    <AsyncButton
      variant="outline"
      size="icon-sm"
      title={verdict === value ? t("MatchVerdict.takeBack") : title}
      onClick={() => give(verdict === value ? 0 : value)}
      icon={<Icon className="size-4" />}
      className={cn("text-muted-foreground", verdict === value && on)}
    />
  );
  return (
    <span className="flex shrink-0 gap-1">
      {/* "!": the outline button's own dark-theme background and border would cover the chosen one */}
      {button(1, t("MatchVerdict.sameSense"), CheckIcon, "border-yes! bg-yes! text-white! hover:bg-yes/90!")}
      {button(-1, t("MatchVerdict.otherSense"), XIcon, "border-no! bg-no! text-white! hover:bg-no/90!")}
    </span>
  );
}

/** "+0.42" / "−0.17" on a green or red ground: how far a picture or review moves the work. */
export function InfluenceBadge({ influence, title, className }: { influence: number; title: string; className?: string }) {
  return (
    <span
      className={cn(
        "rounded px-1.5 py-0.5 font-mono text-xs font-semibold text-white tabular-nums shadow",
        influence >= 0 ? "bg-yes/90" : "bg-no/90",
        className,
      )}
      title={title}
    >
      {influence >= 0 ? "+" : "−"}
      {Math.abs(influence).toFixed(2)}
    </span>
  );
}

/** The strength of a match, with the mark it is like: 👍 3.2. */
export function StrengthBadge({ mark, strength, title }: { mark: number; strength: number; title: string }) {
  return (
    <span
      className={cn(
        "flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 font-mono text-xs font-semibold text-white tabular-nums",
        mark > 0 ? "bg-yes/90" : "bg-no/90",
      )}
      title={title}
    >
      {mark > 0 ? <ThumbsUpIcon className="size-3" /> : <ThumbsDownIcon className="size-3" />}
      {strength.toFixed(1)}
    </span>
  );
}
