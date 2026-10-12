import { useState } from "react";
import { CheckIcon, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * "Подходит? ✓ ✗" under a work found by a search: the user's word on whether it answers the line,
 * what the quality of the search is measured by. Pressed again, the word is taken back. Shown as
 * said at once; the list is not read again for it — its order must not move under the finger.
 */
export function SearchJudge({ value, onJudge }: { value?: boolean; onJudge: (relevant: boolean | null) => Promise<unknown> }) {
  const [said, setSaid] = useState<boolean | undefined>(value);
  const press = (relevant: boolean) => (e: React.MouseEvent) => {
    // The card under it opens on a click: not on this one
    e.stopPropagation();
    const next = said === relevant ? undefined : relevant;
    setSaid(next);
    onJudge(next ?? null).catch(() => setSaid(said));
  };
  return (
    <div className="flex items-center gap-1 text-xs text-muted-foreground">
      <span>Подходит к запросу?</span>
      <button
        type="button"
        title="Подходит"
        onClick={press(true)}
        className={cn("rounded-md p-1 hover:bg-muted", said === true && "bg-yes/15 text-yes")}
      >
        <CheckIcon className="size-3.5" />
      </button>
      <button
        type="button"
        title="Не подходит"
        onClick={press(false)}
        className={cn("rounded-md p-1 hover:bg-muted", said === false && "bg-no/15 text-no")}
      >
        <XIcon className="size-3.5" />
      </button>
    </div>
  );
}
