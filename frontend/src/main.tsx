import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/toast";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import i18n from "./i18n/i18n";
import { App } from "./App";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } },
});

// The server sends keys, not words, but what it asks of others — a catalogue's names — comes in the
// language asked: everything read in another is read again
i18n.on("languageChanged", () => void queryClient.invalidateQueries());

/**
 * The application in the language of the interface: drawn anew, whole, when the user chooses
 * another — the words a part takes from the formatting helpers, not from `t`, change too.
 */
function Root() {
  const { t } = useTranslation();
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <Toaster>
          {/* The last guard: what the parts below do not catch is said here, not a blank page */}
          <ErrorBoundary title={t("Root.crashed")}>
            <App />
          </ErrorBoundary>
        </Toaster>
      </TooltipProvider>
    </QueryClientProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
