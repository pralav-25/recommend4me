import { useEffect, useRef, useState, type ComponentProps, type MouseEvent, type ReactNode } from "react";
import { Loader2Icon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";

/**
 * A button that knows by itself that its handler is still at work (from phototag's AsyncButton).
 *
 * NOT `disabled={busy}` AT EVERY CALLER: "busy" is the state of THE BUTTON, not of the screen;
 * kept outside, it is kept twenty times and forgotten in `finally` nineteen of them, and one flag
 * per screen greys out every button at once.
 *
 * WHAT IT DOES BY ITSELF:
 * - it is disabled while at work, so a second tap does not send a second request;
 * - it shows a spinner IN PLACE OF its icon, not beside it: the button keeps its width and its
 *   neighbours do not jump under the finger;
 * - it catches a failure and shows it — a warning sign, the reason in its title — instead of
 *   failing silently (a request that came back 500 used to look exactly like one not sent);
 * - it does not touch its state after it is gone: the answer may come when the button is not
 *   there any more.
 *
 * A synchronous handler does as well: awaiting a non-promise is legal, and such a button behaves
 * as a plain one.
 */
export function AsyncButton({
  onClick,
  children,
  icon,
  disabled,
  title,
  ...rest
}: Omit<ComponentProps<typeof Button>, "onClick"> & {
  onClick: (e: MouseEvent<HTMLButtonElement>) => unknown | Promise<unknown>;
  /** The icon; a spinner while at work, a warning sign after a failure. */
  icon?: ReactNode;
}) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const alive = useRef(true);
  useEffect(
    () => () => {
      alive.current = false;
    },
    [],
  );

  return (
    <Button
      {...rest}
      disabled={disabled || busy}
      title={failed ? t("AsyncButton.failed", { error: failed }) : title}
      onClick={async (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (busy) return;
        setBusy(true);
        setFailed(null);
        try {
          await onClick(e);
        } catch (err) {
          if (alive.current) setFailed(err instanceof Error ? err.message : String(err));
        } finally {
          if (alive.current) setBusy(false);
        }
      }}
    >
      {/* The icon is decoration — the title names the button — so it is hidden from screen readers */}
      {busy ? (
        <Loader2Icon className="animate-spin" aria-hidden />
      ) : failed ? (
        <TriangleAlertIcon className="text-maybe" aria-hidden />
      ) : (
        icon && <span aria-hidden className="contents">{icon}</span>
      )}
      {children}
    </Button>
  );
}
