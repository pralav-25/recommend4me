import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";

/**
 * A normalized tag's name as a link to its page on the screen of the tags. The press is the link's
 * alone: not of the card or the chip it is on.
 */
export function TagNameLink({ tag, children, className }: { tag: string; children: ReactNode; className?: string }) {
  return (
    <a
      href={screenHref({ kind: "tags", tag })}
      onClick={(e) => {
        e.stopPropagation();
        followLink(e, { kind: "tags", tag });
      }}
      className={cn("underline-offset-2 hover:underline", className)}
    >
      {children}
    </a>
  );
}
