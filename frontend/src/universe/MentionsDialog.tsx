import { useQueries, useQuery } from "@tanstack/react-query";
import { ExternalLinkIcon, Loader2Icon, XIcon } from "lucide-react";
import type { ReactNode } from "react";
import { api, unwrap, type CharacterInfo, type ContentTypeInfo, type FacetValueInfo, type ItemRef, type MentionQuote, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { charactersQuery, classesQuery, keptByClasses, useInclusion, useUniverses } from "./UniverseDialog";

/** The characters a value is of: a character itself, the two of a pairing ("pair:a|b"). */
const charactersOf = (value: string) => (value.startsWith("pair:") ? value.slice("pair:".length).split("|") : [value]);

/**
 * A value the work's texts name, opening on a click what the counts were made of: every passage
 * that names it, the names marked, and — for a character, the two of a pairing — why the
 * dictionary has it: the universe it is of and its classes there, a class or the entry itself to
 * leave out right here, its page in the catalogue to put right there.
 */
export function MentionsDialog({
  type,
  item,
  facet,
  value,
  name,
  url,
  summary,
  universes,
  children,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  facet: string;
  value: string;
  name: string;
  /** The value's page in the catalogue, as the dictionary gives it. */
  url?: string | null;
  /** "упоминается 3 раза" */
  summary?: string;
  /** The universes the work has. */
  universes: FacetValueInfo[];
  /** What opens it. */
  children: ReactNode;
}) {
  return (
    <Dialog>
      <DialogTrigger
        render={<button type="button" className="inline-flex cursor-pointer items-baseline gap-1 underline decoration-dotted underline-offset-2 hover:decoration-solid" />}
        title="Где упоминается и почему в списке"
      >
        {children}
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {url ? (
              <a href={url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 hover:underline" title="Страница в Викиданных">
                {name} <ExternalLinkIcon className="size-4 text-muted-foreground" />
              </a>
            ) : (
              name
            )}
          </DialogTitle>
          {summary && <DialogDescription>{summary}</DialogDescription>}
        </DialogHeader>
        {/* The keys of the work's dialog (grades, steps) stay out of this one; Escape still closes it */}
        <div className="flex flex-col gap-4" onKeyDown={(e) => e.key !== "Escape" && e.stopPropagation()}>
          <Origins type={type} values={charactersOf(value)} universes={universes} />
          <Quotes item={item} facet={facet} value={value} />
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Every passage of the work's texts that names the value, in the order of the work, the names marked. */
function Quotes({ item, facet, value }: { item: ItemRef; facet: string; value: string }) {
  const quotes = useQuery({
    queryKey: ["mentions", item.source, item.item, facet, value],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/items/{source}/{item}/mentions", { params: { path: { source: item.source, item: item.item }, query: { facet, value } } }),
      ),
  });
  if (quotes.isLoading) return <Loader2Icon className="size-4 animate-spin text-muted-foreground" />;
  if (quotes.isError) return <p className="text-destructive">Не удалось найти упоминания: {quotes.error.message}</p>;
  const list = quotes.data ?? [];
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">Где упоминается</h3>
      {list.length === 0 ? (
        <p className="text-muted-foreground">В текстах работы не найден.</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {list.map((q, i) => (
            <li key={i} className="flex flex-col gap-0.5 rounded-lg border px-3 py-2">
              <span className="text-xs text-muted-foreground">{q.place}</span>
              <Marked quote={q} />
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** A passage with the names in it marked. */
function Marked({ quote }: { quote: MentionQuote }) {
  const parts: { text: string; found: boolean }[] = [];
  let at = 0;
  [...quote.highlights]
    .sort((a, b) => a.start - b.start)
    .forEach(({ start, end }) => {
      if (start < at) return;
      if (start > at) parts.push({ text: quote.text.slice(at, start), found: false });
      parts.push({ text: quote.text.slice(start, end), found: true });
      at = end;
    });
  if (at < quote.text.length) parts.push({ text: quote.text.slice(at), found: false });
  return (
    <p className="text-sm leading-relaxed whitespace-pre-line">
      {parts.map((p, i) =>
        p.found ? (
          <mark key={i} className="rounded-sm bg-maybe/35 px-0.5 font-medium text-foreground">
            {p.text}
          </mark>
        ) : (
          <span key={i}>{p.text}</span>
        ),
      )}
    </p>
  );
}

/** Why the dictionary has each of the [values]: the work's universes it is an entry of, with its classes there. */
function Origins({ type, values, universes }: { type: ContentTypeInfo; values: string[]; universes: FacetValueInfo[] }) {
  const dictionary = useUniverses(type.id, universes.length > 0);
  const linked = universes
    .map((v) => dictionary.data?.find((u) => u.value === v.key))
    .filter((u): u is UniverseInfo => u !== undefined);
  const characters = useQueries({ queries: linked.map((u) => charactersQuery(type.id, u)) });
  if (dictionary.isLoading || characters.some((q) => q.isLoading)) return <Loader2Icon className="size-4 animate-spin text-muted-foreground" />;
  const found = values.flatMap((value) =>
    linked.flatMap((u, i) => {
      const entry = characters[i].data?.find((c) => c.value === value);
      return entry ? [{ universe: u, entry }] : [];
    }),
  );
  if (found.length === 0) return null;
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">Почему в списке</h3>
      {found.map(({ universe, entry }) => (
        <Origin key={`${universe.value} ${entry.value}`} type={type} universe={universe} entry={entry} />
      ))}
    </section>
  );
}

/**
 * One entry of one universe: kept as a character by its classes there, or by the user's word on
 * it alone. A class pressed leaves out (or keeps) every entry of it in the universe; the entry can
 * be left out by itself; its page in the catalogue is where a wrong class is put right.
 */
function Origin({ type, universe, entry }: { type: ContentTypeInfo; universe: UniverseInfo; entry: CharacterInfo }) {
  const classes = useQuery(classesQuery(type.id, universe));
  const inclusion = useInclusion(type.id, universe);
  const byId = new Map((classes.data ?? []).map((c) => [c.class, c]));
  const own = entry.classes.map((id) => byId.get(id)).filter((c) => c !== undefined);
  return (
    <div className="flex flex-col gap-1.5 rounded-lg border px-3 py-2">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <span className={cn("font-medium", !entry.included && "line-through decoration-muted-foreground/60")}>{entry.names[0] ?? entry.character}</span>
        <span className="text-xs text-muted-foreground">во вселенной «{universe.name}»</span>
        <a href={entry.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          {entry.character} <ExternalLinkIcon className="size-3" />
        </a>
      </div>
      {entry.description && <div className="text-xs text-muted-foreground">{entry.description}</div>}
      <div className="text-xs text-muted-foreground">
        {entry.choice != null
          ? entry.included
            ? "Оставлен вашим выбором, что бы ни говорили классы."
            : "Убран вашим выбором."
          : entry.classes.length === 0
            ? "В Викиданных у него нет классов — оставлен: ничто не говорит, что это не персонаж."
            : "В списке по классам — нажмите на неверный, чтобы убрать из вселенной все его записи:"}
      </div>
      {classes.isLoading && <Loader2Icon className="size-4 animate-spin text-muted-foreground" />}
      {own.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {own.map((c) => (
            <AsyncButton
              key={c.class}
              size="xs"
              variant={c.included ? "secondary" : "outline"}
              aria-pressed={c.included}
              onClick={() => inclusion.setClass(c)}
              title={`${c.included ? "Оставлены как персонажи" : "Убраны"}${c.choice != null ? " — ваш выбор" : ""}. Нажмите, чтобы ${c.included ? "убрать" : "оставить"} все ${c.count} во вселенной`}
              className={cn("h-auto min-h-6 whitespace-normal", !c.included && "text-muted-foreground line-through decoration-muted-foreground/60")}
            >
              {c.choice != null && <span className="text-primary">•</span>}
              {c.name} <span className="tabular-nums">{c.count}</span>
            </AsyncButton>
          ))}
        </div>
      )}
      <div>
        <AsyncButton
          size="xs"
          variant="ghost"
          onClick={() => inclusion.setEntry(entry, keptByClasses(entry, byId))}
          icon={entry.included ? <XIcon /> : undefined}
          disabled={classes.isLoading}
        >
          {entry.included ? "Убрать из вселенной только его" : "Вернуть во вселенную"}
        </AsyncButton>
      </div>
    </div>
  );
}
