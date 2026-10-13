import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckIcon, CopyIcon, ExternalLinkIcon, Loader2Icon, RadarIcon } from "lucide-react";
import { api, unwrap, type ItemRef } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { findSource, useTypes } from "@/contenttype/types";
import { ago, formatDate } from "@/i18n";
import { ItemRefDialog } from "@/item/ItemRefDialog";

const DEFAULT_ADDRESS = "http://127.0.0.1:8095";

/**
 * Browser tracking: how to install the Firefox extension that sends the pages of the tracked
 * sites the user opens, and the pages it sent last.
 */
export function CaptureDialog({ open, onOpenChange: setOpen }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [item, setItem] = useState<ItemRef | null>(null);
  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
          {open && (
            <CaptureDetails
              onOpenItem={(ref) => {
                setOpen(false);
                setItem(ref);
              }}
            />
          )}
        </DialogContent>
      </Dialog>
      <ItemRefDialog item={item} onClose={() => setItem(null)} />
    </>
  );
}

function CaptureDetails({ onOpenItem }: { onOpenItem: (ref: ItemRef) => void }) {
  const types = useTypes();
  const patterns = useQuery({
    queryKey: ["capture", "patterns"],
    queryFn: async () => unwrap(await api.GET("/api/capture/patterns")),
  });
  const extension = useQuery({
    queryKey: ["capture", "extension"],
    queryFn: async () => unwrap(await api.GET("/api/capture/extension")),
  });
  const recent = useQuery({
    queryKey: ["capture", "recent"],
    queryFn: async () => unwrap(await api.GET("/api/capture/recent")),
    // While the dialog is open the pages come in as the user browses
    refetchInterval: 3000,
  });
  const titleOf = (source: string) => findSource(types.data, source)?.source.title ?? source;
  const homepageOf = (source: string) => findSource(types.data, source)?.source.homepage;
  const tracked = (patterns.data ?? []).filter((p) => p.patterns.length > 0);

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <RadarIcon className="size-5" /> Слежение в браузере
        </DialogTitle>
        <DialogDescription>
          Расширение для Firefox присылает сюда страницы сайтов, которые вы открываете, — так приложение узнаёт о работах,
          ничего не запрашивая у сайта само, и видит их такими, какими их видите вы (с вашим входом).
        </DialogDescription>
      </DialogHeader>

      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm">
        <li>
          Откройте в Firefox адрес <Code>about:debugging#/runtime/this-firefox</Code> — его нужно вставить в адресную строку
          вручную, по ссылке Firefox его не откроет.
        </li>
        <li>
          Нажмите «Загрузить временное дополнение…» и выберите файл расширения — вставьте его путь в поле имени файла:
          <ManifestPath manifest={extension.data?.manifest} loading={extension.isLoading} />
        </li>
        <li>
          Готово: расширение работает, пока Firefox не перезапустится; после перезапуска его нужно загрузить снова тем же
          способом. После обновления приложения нажмите у расширения «Перезагрузить» на той же странице.
        </li>
      </ol>

      <SafariSteps />

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">Что расширение показывает на сайтах</h4>
        <p className="text-muted-foreground">
          На странице работы — панель с прогнозом, кнопками оценки и тем, на чём держится прогноз. У каждого тега сайта — в
          скобках уверенность модели (жёлтым, если скорее нет) и ваши ✓ «есть» и ✕ «нет»; повторное нажатие снимает ответ.
          После них — ваши теги, подсказки пунктиром и поле, чтобы добавить свой. На ficbook под блоком «Пэйринг и персонажи»
          — вычисленные пейринги и главные персонажи. Под отзывами и картинками — «+ / −»: повод выбрать работу или
          отказаться от неё. В лентах и поиске на каждой карточке — прогноз и ваша оценка. Выключается в настройках
          расширения.
        </p>
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">Что присылает расширение</h4>
        <p className="text-muted-foreground">
          Только страницы этих сайтов — и только по адресу, на который оно настроено: по умолчанию <Code>{DEFAULT_ADDRESS}</Code>
          , это приложение на этом компьютере. Адрес меняется в настройках расширения (about:addons → recommend4me →
          Настройки). Страницы остальных сайтов никуда не уходят.
        </p>
        {patterns.isLoading ? (
          <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
        ) : tracked.length === 0 ? (
          <p className="text-muted-foreground">Ни один источник сейчас не следит за страницами в браузере.</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {tracked.map((p) => (
              <li key={p.source} className="flex flex-wrap items-baseline gap-x-2">
                {homepageOf(p.source) ? (
                  <a href={homepageOf(p.source)} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                    {titleOf(p.source)}
                  </a>
                ) : (
                  <span className="font-medium">{titleOf(p.source)}</span>
                )}
                <span className="truncate font-mono text-xs text-muted-foreground" title={p.patterns.join("\n")}>
                  {p.patterns.length === 1 ? p.patterns[0] : `${p.patterns.length} шаблонов адресов`}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="flex items-center gap-2 font-semibold">
          Последние присланные страницы
          {recent.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        </h4>
        {recent.data && recent.data.length === 0 && (
          <p className="text-muted-foreground">Пока ни одной. Откройте страницу работы на одном из сайтов выше — она появится здесь через пару секунд.</p>
        )}
        {recent.isError && <p className="text-destructive">Не удалось загрузить список</p>}
        <ul className="flex flex-col divide-y rounded-lg border">
          {(recent.data ?? []).map((p) => (
            <li key={`${p.url}-${p.capturedAt}`} className="flex items-center gap-2 px-3 py-1.5">
              <span className="w-24 shrink-0 text-xs text-muted-foreground" title={formatDate(p.capturedAt)}>
                {ago(p.capturedAt)}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{titleOf(p.source)}</span>
              {p.item ? (
                <button
                  className="min-w-0 flex-1 truncate text-left underline-offset-2 hover:underline"
                  onClick={() => onOpenItem({ source: p.source, item: p.item! })}
                  title="Открыть работу"
                >
                  {p.title ?? p.item}
                </button>
              ) : (
                <span className="min-w-0 flex-1 truncate text-muted-foreground" title={p.url}>
                  {p.url}
                </span>
              )}
              <a href={p.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title="Страница на сайте">
                <ExternalLinkIcon className="size-3.5" />
              </a>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

/** The Userscripts app in the App Store: an extension of Safari that runs the scripts it is given. */
const USERSCRIPTS = "https://apps.apple.com/app/userscripts/id1463298887";

/**
 * The same on the iPad, where Safari takes no extension but from the App Store: the Userscripts
 * app runs the application's userscript, made of the extension's own scripts. The script calls
 * the application at the address it was taken from — so it is taken on the iPad, at the address
 * the iPad reaches the application at: over https, which `tailscale serve` gives.
 */
function SafariSteps() {
  const origin = window.location.origin;
  const script = `${origin}/userscript/recommend4me.user.js`;
  const secure = window.location.protocol === "https:";
  return (
    <section className="flex flex-col gap-2 text-sm">
      <h4 className="font-semibold">Safari на iPad</h4>
      <p className="text-muted-foreground">
        Расширение без App Store в Safari не поставить, поэтому на iPad то же самое делает скрипт для бесплатного приложения
        Userscripts: присылает страницы и показывает на них прогноз, оценку и теги.
      </p>
      <ol className="flex list-decimal flex-col gap-2 pl-5">
        <li>
          На этом компьютере один раз отдайте приложение по https через Tailscale: в настройках сети Tailscale (DNS) включите
          «HTTPS Certificates», затем выполните <Code>tailscale serve --bg 8095</Code>. Приложение откроется по адресу вида{" "}
          <Code>https://имя-компьютера.ваша-сеть.ts.net</Code> — без порта.
        </li>
        <li>
          На iPad поставьте{" "}
          <a href={USERSCRIPTS} target="_blank" rel="noreferrer" className="underline underline-offset-2">
            Userscripts
          </a>{" "}
          из App Store, откройте его один раз и выберите папку для скриптов. Затем в «Настройки → Приложения → Safari →
          Расширения → Userscripts» включите его и разрешите на всех сайтах.
        </li>
        <li>
          В Safari на iPad откройте это окно по https-адресу из шага 1 и нажмите ссылку на скрипт ниже, затем значок Userscripts
          в адресной строке → «Install». Скрипт запомнит адрес, с которого его взяли, и будет обновляться оттуда же.
          <div className="mt-1 flex flex-col gap-1">
            <a href={script} className="font-mono text-xs break-all underline underline-offset-2">
              {script}
            </a>
            {!secure && (
              <span className="text-xs text-maybe">
                Это окно открыто не по https: взятый отсюда скрипт будет обращаться к {origin}, а iPad туда, скорее всего, не
                дотянется. Откройте приложение на iPad по адресу из шага 1.
              </span>
            )}
          </div>
        </li>
      </ol>
    </section>
  );
}

/** The path of the extension's manifest.json on this machine, with a button to copy it. */
function ManifestPath({ manifest, loading }: { manifest?: string; loading: boolean }) {
  const [copied, setCopied] = useState(false);
  if (loading) return <Loader2Icon className="mt-1 size-4 animate-spin text-muted-foreground" />;
  if (!manifest) {
    return (
      <p className="mt-1 text-destructive">
        Приложение не знает, где лежит расширение: запустите его из установленной папки (там есть папка <Code>extension</Code>).
      </p>
    );
  }
  const copy = async () => {
    await navigator.clipboard.writeText(manifest);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <div className="mt-1 flex items-center gap-2">
      <Code>{manifest}</Code>
      <Button variant="outline" size="sm" onClick={copy} title="Скопировать путь">
        {copied ? <CheckIcon /> : <CopyIcon />}
        {copied ? "Скопировано" : "Скопировать"}
      </Button>
    </div>
  );
}

function Code({ children }: { children: ReactNode }) {
  return <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs break-words">{children}</code>;
}
