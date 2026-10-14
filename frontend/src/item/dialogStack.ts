import { useEffect, useRef } from "react";
import type { ItemRef } from "@/api/client";
import { writeItemHash } from "./itemHash";

/**
 * THE WORK DIALOGS OPEN ONE OVER ANOTHER — a related work's link opens its dialog over the open
 * one. The keys and the address are the topmost's alone: a digit grades the work the user looks
 * at, never the one under it too; closed, the address names the dialog left on top, or none.
 */
interface Entry {
  readonly id: symbol;
  ref: ItemRef | null;
}

const stack: Entry[] = [];

function top(): Entry | undefined {
  return stack[stack.length - 1];
}

/** A work's dialog is open: the keys are its, not the list's under it. */
export function workDialogOpen(): boolean {
  return stack.length > 0;
}

/** The address names the work of the dialog on top, or none when none is open. */
export function writeTopHash() {
  writeItemHash(top()?.ref ?? null);
}

/**
 * A work dialog in the stack while it shows [ref]: the address names its work while it is on top.
 * Returns whether it is on top now — asked by its keys at the moment of a press.
 */
export function useStackedDialog(ref: ItemRef | null): () => boolean {
  const entry = useRef<Entry>({ id: Symbol("work dialog"), ref });
  entry.current.ref = ref;
  const open = ref !== null;

  useEffect(() => {
    if (!open) return;
    const own = entry.current;
    stack.push(own);
    return () => {
      const wasTop = top() === own;
      stack.splice(stack.lastIndexOf(own), 1);
      if (wasTop) writeTopHash();
    };
  }, [open]);

  // Another work in the same dialog: the address follows it, if the dialog is on top
  const source = ref?.source;
  const item = ref?.item;
  useEffect(() => {
    if (source !== undefined && item !== undefined && top() === entry.current) writeItemHash({ source, item });
  }, [source, item]);

  return () => top() === entry.current;
}
