import { Component, type ReactNode } from "react";
import { TriangleAlertIcon } from "lucide-react";
import i18n from "@/i18n/i18n";
import { cn } from "@/lib/utils";

type Props = {
  children: ReactNode;
  /** When it changes (another work opened, another type chosen), the part is drawn anew. */
  resetKey?: string;
  /** What failed, for the user: "The work's card did not open". */
  title?: string;
  /** A small part — a section of a card, a button of the header: one line in its place. */
  compact?: boolean;
  className?: string;
};

type State = { error: Error | null; key?: string };

/**
 * A part of the interface that failed to draw shows what went wrong in its place instead of
 * taking the whole application down with it: React unmounts everything above an error nobody
 * catches. Put at the parts that stand by themselves — the application, the list, the filters,
 * every dialog, every section of a work's card.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, key: this.props.resetKey };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.resetKey !== state.key ? { error: null, key: props.resetKey } : null;
  }

  componentDidCatch(error: Error) {
    console.error(`recommend4me: ${this.props.title ?? "a part of the interface"} failed to draw`, error);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    const title = this.props.title ?? i18n.t("ErrorBoundary.failed");
    if (this.props.compact)
      return (
        <span className={cn("inline-flex items-center gap-1 text-xs text-destructive", this.props.className)} title={error.message}>
          <TriangleAlertIcon className="size-3.5 shrink-0" /> {title}
        </span>
      );
    return (
      <div className={cn("m-4 rounded-lg border border-destructive/50 bg-destructive/5 p-4 text-sm", this.props.className)}>
        <div className="font-semibold text-destructive">{title}</div>
        <div className="mt-1 font-mono text-xs break-all text-muted-foreground">{error.message}</div>
      </div>
    );
  }
}
