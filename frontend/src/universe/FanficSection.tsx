import { useState, type ReactNode } from "react";
import { useQueries } from "@tanstack/react-query";
import { CheckIcon, Loader2Icon, PlusIcon, SearchIcon, XIcon } from "lucide-react";
import type { Candidate, ContentTypeInfo, FacetValueInfo, ItemDetails, ItemRef, SourceInfo, SuggestedValue, UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { FacetAnswers } from "@/correction/FacetAnswers";
import { FacetValue, MoreCandidates } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { useStableOrder } from "@/facet/valueOrder";
import { OriginalOf } from "@/item/WorkFacts";
import { percent, plural } from "@/i18n";
import { cn } from "@/lib/utils";
import { SuggestedChip } from "@/suggestion/Suggestions";
import { useCandidates, useSuggestions } from "@/suggestion/useSuggestions";
import { MentionsDialog } from "./MentionsDialog";
import { CatalogueSearch, charactersQuery, useUniverses } from "./Universes";

/** The facets the application gives every work of a type with universes (the backend's UniverseFacets). */
const KIND = "kind";
const UNIVERSE = "universe";
const CHARACTERS = "characters";
const SECONDARY_CHARACTERS = "secondary-characters";
const PAIRINGS = "pairings";

/** The facets this section answers: the generic list of the work's facets leaves them out. */
export const FANFIC_FACETS: readonly string[] = [KIND, UNIVERSE, CHARACTERS, SECONDARY_CHARACTERS, PAIRINGS];

const FANFICTION = "fanfiction";
const REALISM = "realism";
const ALTERNATIVE_HISTORY = "alternative-history";
const ORIGINAL = "original";

/** The kinds of a work, as the control names them. */
const KINDS = [
  { key: FANFICTION, name: "Фанфик" },
  { key: REALISM, name: "Реализм" },
  { key: ALTERNATIVE_HISTORY, name: "Альтернативная история" },
  { key: ORIGINAL, name: "Оригинал" },
];

/** The kinds of a work of no universe of another's: our world, ours changed, or a world of its own. */
const UNIVERSELESS: readonly string[] = [REALISM, ALTERNATIVE_HISTORY, ORIGINAL];

/** Known to have no universe: every kind the work has, the user's answers counted, is of no universe. */
const universeless = (kinds: FacetValueInfo[]) => kinds.length > 0 && kinds.every((k) => UNIVERSELESS.includes(k.key));

/** The original characters, of no universe: every work may have them besides its universes' characters. */
const ORIGINAL_CHARACTERS = [
  { key: "oc:female", name: "ОЖП (оригинальный женский персонаж)" },
  { key: "oc:male", name: "ОМП (оригинальный мужской персонаж)" },
];

/** The key of the pairing of two characters: one whatever their order. */
const pairingKey = (a: string, b: string) => "pair:" + [a, b].sort().join("|");

/** The work has the value: the site or the model gave it, or the user added it, and the user did not take it away — not one only offered. */
const has = (v: FacetValueInfo) => v.corrected !== "REMOVED" && !v.offered;

/** The user said the work has the value. */
const answeredYes = (v: FacetValueInfo | undefined) => v?.corrected === "ADDED" || v?.corrected === "CONFIRMED";

const SELECT =
  "h-8 min-w-0 flex-1 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring dark:bg-input/30";

/**
 * Whether the work is fan fiction, of which universes, with which main characters and pairings —
 * everything the site says on it, the model's chances and the user's answers, in one place. For
 * the works of a type with universes only.
 */
export function FanficSection({
  type,
  source,
  details: d,
  corrections,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  corrections: Corrections;
}) {
  const ref = { source: d.summary.source, item: d.summary.item };
  const suggestions = useSuggestions(ref, source);
  const facetOf = (key: string) => d.allFacets.find((f) => f.facet === key);
  const valuesOf = (key: string) => facetOf(key)?.values ?? [];
  const suggestedOf = (key: string) => suggestions.data?.find((s) => s.facet === key)?.suggested ?? [];
  const labelOf = (key: string, fallback: string) => facetOf(key)?.label ?? source.facets.find((f) => f.key === key)?.label ?? fallback;

  const original = universeless(valuesOf(KIND).filter(has));
  // The site's facet above the kind; above the universes too, unless it is the same one (ficbook's fandoms)
  const siteOf = (key: string) => source.facets.find((f) => f.key === key)?.originalFacet;
  const universeSite = siteOf(UNIVERSE) !== siteOf(KIND);

  return (
    <section className="flex flex-col gap-4">
      <div>
        <OriginalOf source={source} facets={d.allFacets} facet={KIND} />
        <KindChoice label={labelOf(KIND, "Тип вселенной")} values={valuesOf(KIND)} suggested={suggestedOf(KIND)} corrections={corrections} />
      </div>
      <div>
        {universeSite && <OriginalOf source={source} facets={d.allFacets} facet={UNIVERSE} />}
        {!original && (
          <Universes
            type={type}
            label={labelOf(UNIVERSE, "Вселенная")}
            values={valuesOf(UNIVERSE)}
            complete={facetOf(UNIVERSE)?.complete === true}
            suggested={suggestedOf(UNIVERSE)}
            corrections={corrections}
          />
        )}
      </div>
      <OriginalOf source={source} facets={d.allFacets} facet={CHARACTERS} />
      <Characters
        type={type}
        item={ref}
        facet={CHARACTERS}
        label={labelOf(CHARACTERS, "Главные персонажи")}
        what="главные персонажи"
        others="Других главных нет"
        values={valuesOf(CHARACTERS)}
        complete={facetOf(CHARACTERS)?.complete === true}
        elsewhere={valuesOf(SECONDARY_CHARACTERS).filter(has)}
        universes={valuesOf(UNIVERSE).filter(has)}
        corrections={corrections}
      />
      <Characters
        type={type}
        item={ref}
        facet={SECONDARY_CHARACTERS}
        label={labelOf(SECONDARY_CHARACTERS, "Второстепенные персонажи")}
        what="второстепенные персонажи"
        others="Других второстепенных нет"
        values={valuesOf(SECONDARY_CHARACTERS)}
        complete={facetOf(SECONDARY_CHARACTERS)?.complete === true}
        elsewhere={valuesOf(CHARACTERS).filter(has)}
        universes={valuesOf(UNIVERSE).filter(has)}
        corrections={corrections}
      />
      <Pairings
        type={type}
        item={ref}
        universes={valuesOf(UNIVERSE).filter(has)}
        label={labelOf(PAIRINGS, "Пэйринги")}
        values={valuesOf(PAIRINGS)}
        complete={facetOf(PAIRINGS)?.complete === true}
        characters={valuesOf(CHARACTERS).filter(has)}
        corrections={corrections}
      />
    </section>
  );
}

/**
 * The section to read: whether the work is fan fiction, of which universes, with which main
 * characters and pairings — the names alone, the user's filled with a ✓, nothing the user took
 * away. What the model gave or suggests and the user has not answered on is counted in a line that
 * opens the marking ([onMark]).
 */
export function FanficSummary({ source, details: d, onMark }: { source: SourceInfo; details: ItemDetails; onMark: () => void }) {
  const ref = { source: d.summary.source, item: d.summary.item };
  const suggestions = useSuggestions(ref, source);
  const valuesOf = (key: string) => (d.allFacets.find((f) => f.facet === key)?.values ?? []).filter(has);
  const kinds = valuesOf(KIND);
  const original = universeless(kinds);
  // The model's word the user has not answered on: values it gave, values it suggests
  const open =
    FANFIC_FACETS.flatMap((f) => valuesOf(f)).filter((v) => v.inferred && !v.corrected).length +
    (suggestions.data ?? []).filter((s) => FANFIC_FACETS.includes(s.facet)).reduce((n, s) => n + s.suggested.length, 0);

  const line = (label: string, values: FacetValueInfo[]) =>
    values.length > 0 && (
      <div className="flex flex-col gap-1">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className="flex flex-wrap gap-1">
          {values.map((v) => (
            <Badge key={v.key} variant="secondary" className={cn("font-normal", v.corrected && "border-yes/50 bg-yes/15")} title={v.corrected ? undefined : "Вы не отвечали"}>
              {v.corrected && <CheckIcon className="size-3 text-yes" />}
              {v.name}
            </Badge>
          ))}
        </div>
      </div>
    );

  if (original) {
    return <section className="text-sm text-muted-foreground">{kinds.map((k) => KINDS.find((x) => x.key === k.key)?.name ?? k.name).join(", ")}</section>;
  }
  if (kinds.length === 0 && valuesOf(UNIVERSE).length === 0) return null;
  return (
    <section className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold">Фанфик</h4>
      <OriginalOf source={source} facets={d.allFacets} facet={UNIVERSE} />
      {line("Вселенная", valuesOf(UNIVERSE))}
      <OriginalOf source={source} facets={d.allFacets} facet={CHARACTERS} />
      {line("Главные персонажи", valuesOf(CHARACTERS))}
      {line("Второстепенные персонажи", valuesOf(SECONDARY_CHARACTERS))}
      {line("Пэйринги", valuesOf(PAIRINGS))}
      {open > 0 && (
        <Button variant="link" size="sm" className="h-auto self-start p-0 text-xs" onClick={onMark}>
          Без вашего ответа: {open} — разобрать
        </Button>
      )}
    </section>
  );
}

/** A part of the section: its label, then the rest — the site's own word on it is shown above the section. */
function Block({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      {children}
    </div>
  );
}

/**
 * The work's values of a facet as chips, the suggested ones dashed after them, a dash when there
 * are none; with [item], "…" for more of its candidates.
 */
function Chips({
  facet,
  values,
  suggested = [],
  item,
  corrections,
}: {
  facet: string;
  values: FacetValueInfo[];
  suggested?: SuggestedValue[];
  item?: ItemRef;
  corrections: Corrections;
}) {
  const ordered = useStableOrder(values);
  if (values.length === 0 && suggested.length === 0 && !item) return <div className="text-sm text-muted-foreground">—</div>;
  return (
    <div className="flex flex-wrap items-center gap-1">
      {values.length === 0 && suggested.length === 0 && <span className="text-sm text-muted-foreground">—</span>}
      {ordered.map((v) => (
        <FacetValue key={v.key} facet={facet} value={v} corrections={corrections} />
      ))}
      {suggested.map((v) => (
        <SuggestedChip key={v.key} facet={facet} value={v} corrections={corrections} />
      ))}
      {item && <MoreCandidates item={item} facet={facet} shown={[...values, ...suggested]} corrections={corrections} />}
    </div>
  );
}

/**
 * Fan fiction, realism, an alternative history or an original work: one list to choose from — a
 * row of buttons no longer fits the side of the dialog. Each kind shows the model's chance of it
 * while the user has not answered; while nothing is chosen the list names what the site or the
 * model gave. Choosing a kind confirms it and says no to the others the work has; choosing none
 * takes the answers back.
 */
function KindChoice({
  label,
  values,
  suggested,
  corrections,
}: {
  label: string;
  values: FacetValueInfo[];
  suggested: SuggestedValue[];
  corrections: Corrections;
}) {
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const valueOf = (key: string) => values.find((v) => v.key === key);
  const chosen = KINDS.find((k) => answeredYes(valueOf(k.key)));
  // What the site or the model gave, while the user has not chosen
  const given = KINDS.filter((k) => {
    const v = valueOf(k.key);
    return v !== undefined && has(v);
  });

  /** The model's chance of the kind: of the work's value, or of the suggestion. */
  const chanceOf = (key: string) => valueOf(key)?.chance ?? suggested.find((x) => x.key === key)?.chance ?? null;

  /** Takes every answer on the kinds back: the site's and the model's word stands again. */
  const takeBack = async () => {
    for (const k of KINDS) if (valueOf(k.key)?.corrected) await corrections.resetFacet(KIND, k.key);
  };

  const choose = async (key: string) => {
    await corrections.setFacet(KIND, { key }, true);
    // A kind only the user gave is taken back; one the site or the model gave is answered no
    for (const other of KINDS.filter((k) => k.key !== key).map((k) => k.key)) {
      const theirs = valueOf(other);
      if (theirs?.corrected === "ADDED") await corrections.resetFacet(KIND, other);
      else if (theirs && has(theirs)) await corrections.setFacet(KIND, { key: other }, false);
    }
  };

  const change = async (key: string) => {
    setSaving(true);
    setFailed(null);
    try {
      await (key === "" ? takeBack() : choose(key));
    } catch (e) {
      setFailed(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const none = given.length > 0 ? `Не выбран — ${given.map((k) => k.name.toLowerCase()).join(", ")} без вашего ответа` : "Не выбран";

  // One line: the label, the user's mark and the list
  return (
    <div className="flex flex-col gap-1">
      <label className="flex items-center gap-1.5">
        <span className="shrink-0 text-xs text-muted-foreground">{label}</span>
        {saving ? <Loader2Icon className="size-4 shrink-0 animate-spin text-muted-foreground" /> : chosen && <CheckIcon className="size-4 shrink-0 text-yes" />}
        <select
          value={chosen?.key ?? ""}
          onChange={(e) => change(e.target.value)}
          disabled={saving}
          aria-label={label}
          title={chosen ? "Ваш ответ. Выберите «не выбран», чтобы снять его" : "Выберите, чтобы ответить"}
          className={SELECT}
        >
          <option value="">{chosen ? "Не выбран (снять ответ)" : none}</option>
          {KINDS.map((k) => {
            const v = valueOf(k.key);
            const chance = v?.corrected ? null : chanceOf(k.key);
            return (
              <option key={k.key} value={k.key}>
                {k.name}
                {chance != null ? ` · ${percent(chance)}` : ""}
                {v?.corrected === "REMOVED" ? " · вы ответили «нет»" : ""}
              </option>
            );
          })}
        </select>
      </label>
      {failed && <div className="text-xs text-destructive">{failed}</div>}
    </div>
  );
}

/**
 * The work's universes and the suggested ones; a universe of the dictionary is added by the
 * picker, one not yet in the dictionary is found in the catalogue — added to the dictionary and
 * linked to the work at once. Once the user says there are no others ([complete]), the work's own
 * alone are shown: every other is taken from it, nothing more is offered.
 */
function Universes({
  type,
  label,
  values,
  complete,
  suggested,
  corrections,
}: {
  type: ContentTypeInfo;
  label: string;
  values: FacetValueInfo[];
  complete: boolean;
  suggested: SuggestedValue[];
  corrections: Corrections;
}) {
  const own = values.filter(has);
  const allNamed = own.length > 0 && <AllNamed facet={UNIVERSE} what="вселенные" others="Других вселенных нет" complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <Block label={label}>
        <Chips facet={UNIVERSE} values={own} corrections={corrections} />
        <div>{allNamed}</div>
      </Block>
    );
  return <OpenUniverses type={type} label={label} values={values} suggested={suggested} corrections={corrections} allNamed={allNamed} />;
}

/**
 * "Других … нет": the user's word that the work's values of the facet are all named — the others
 * are taken from it, not offered and rejected for the model; pressed again, taken back.
 */
function AllNamed({
  facet,
  what,
  others,
  complete,
  corrections,
}: {
  facet: string;
  /** The values in the plural: "вселенные", "пэйринги". */
  what: string;
  /** The button's text: "Других вселенных нет". */
  others: string;
  complete: boolean;
  corrections: Corrections;
}) {
  return (
    <AsyncButton
      size="sm"
      variant={complete ? "secondary" : "ghost"}
      aria-pressed={complete}
      onClick={() => corrections.setComplete(facet, !complete)}
      icon={complete ? <CheckIcon /> : undefined}
      title={
        complete
          ? `Названы все ${what} работы: остальные не предлагаются и для модели отклонены. Нажмите, чтобы снять`
          : `Все ${what} работы уже названы: остальные не предлагать и считать отклонёнными`
      }
    >
      {others}
    </AsyncButton>
  );
}

/** The work's universes while others may be: every value, the suggested ones, the picker and the search in the catalogue. */
function OpenUniverses({
  type,
  label,
  values,
  suggested,
  corrections,
  allNamed,
}: {
  type: ContentTypeInfo;
  label: string;
  values: FacetValueInfo[];
  suggested: SuggestedValue[];
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const dictionary = useUniverses(type.id);
  const linked = (value: string) => values.some((v) => v.key === value && has(v));
  const link = (value: string) => corrections.setFacet(UNIVERSE, { key: value }, true);
  const options: Option[] = (dictionary.data ?? []).map((u) => ({
    key: u.value,
    name: u.name,
    note: u.description,
    names: [u.name],
    taken: linked(u.value),
  }));

  return (
    <Block label={label}>
      <Chips facet={UNIVERSE} values={values} suggested={suggested} corrections={corrections} />
      <div className="flex flex-wrap items-start gap-1">
        <Picker
          label="Добавить вселенную"
          placeholder="Найти вселенную словаря"
          options={options}
          loading={dictionary.isLoading}
          error={dictionary.error?.message}
          empty="Словарь вселенных пуст: найдите вселенную в Викиданных"
          onPick={link}
        />
        <Dialog>
          <DialogTrigger render={<Button variant="ghost" size="sm" title="Найти вселенную в Викиданных, добавить её в словарь и связать с работой" />}>
            <SearchIcon /> Найти в Викиданных…
          </DialogTrigger>
          <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
            <DialogHeader>
              <DialogTitle>Найти вселенную в Викиданных</DialogTitle>
              <DialogDescription>Найденная вселенная добавляется в словарь вместе с персонажами и сразу связывается с этой работой.</DialogDescription>
            </DialogHeader>
            {/* The keys of the work's dialog (grades, steps) stay out of the search; Escape still closes it */}
            <div onKeyDown={(e) => e.key !== "Escape" && e.stopPropagation()}>
              <CatalogueSearch type={type} dictionary={dictionary.data} forWork={{ linked, link }} />
            </div>
          </DialogContent>
        </Dialog>
        {allNamed}
      </div>
    </Block>
  );
}

/**
 * The work's main or secondary characters ([facet]). Once the user says there are no others
 * ([complete]), the work's own alone are shown: every other is taken from it, nothing more is
 * offered.
 */
function Characters({
  type,
  item,
  facet,
  label,
  what,
  others,
  values,
  complete,
  elsewhere,
  universes,
  corrections,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  facet: string;
  label: string;
  /** The characters in the plural, for the hint of [others]: "главные персонажи". */
  what: string;
  /** The text of the button saying there are no others: "Других главных нет". */
  others: string;
  values: FacetValueInfo[];
  complete: boolean;
  /** The characters the work has in its other facet of characters: a character is in one of them only. */
  elsewhere: FacetValueInfo[];
  /** The universes the work has. */
  universes: FacetValueInfo[];
  corrections: Corrections;
}) {
  const own = values.filter(has);
  const allNamed = own.length > 0 && <AllNamed facet={facet} what={what} others={others} complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <Block label={label}>
        <Chips facet={facet} values={own} corrections={corrections} />
        <div>{allNamed}</div>
      </Block>
    );
  return (
    <OpenCharacters
      type={type}
      item={item}
      facet={facet}
      label={label}
      values={values}
      elsewhere={elsewhere}
      universes={universes}
      corrections={corrections}
      allNamed={allNamed}
    />
  );
}

/**
 * The work's characters of the [facet] while others may be. The picker offers the characters of
 * every universe the work is linked to, by any of their names — not those of its other facet of
 * characters — the original characters, and a name of the user's own.
 */
function OpenCharacters({
  type,
  item,
  facet,
  label,
  values,
  elsewhere,
  universes,
  corrections,
  allNamed,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  facet: string;
  label: string;
  values: FacetValueInfo[];
  elsewhere: FacetValueInfo[];
  universes: FacetValueInfo[];
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const dictionary = useUniverses(type.id, universes.length > 0);
  const linked = universes
    .map((v) => dictionary.data?.find((u) => u.value === v.key))
    .filter((u): u is UniverseInfo => u !== undefined);
  const characters = useQueries({ queries: linked.map((u) => charactersQuery(type.id, u)) });

  const taken = (key: string) => values.some((v) => v.key === key && has(v));
  // A character of the work's other facet of characters is not offered: it is moved by taking it from there
  const other = new Set(elsewhere.map((v) => v.key));
  // A character of several of the work's universes is offered once, with every universe it is of;
  // the entries the user left out of a universe (its places, its spells) are not offered
  const byKey = new Map<string, Option & { universes: string[] }>();
  linked.forEach((u, i) => {
    for (const c of (characters[i].data ?? []).filter((c) => c.included)) {
      const key = `${u.catalogue}:${c.character}`;
      if (other.has(key)) continue;
      const known = byKey.get(key);
      if (known) known.universes.push(u.name);
      else
        byKey.set(key, {
          key,
          name: c.names[0] ?? c.character,
          description: c.description ?? undefined,
          names: c.names.length > 0 ? c.names : [c.character],
          universes: [u.name],
          taken: taken(key),
        });
    }
  });
  const options: Option[] = [
    ...[...byKey.values()].map((o) => ({
      ...o,
      note: [o.names.slice(1).join(", "), o.universes.join(", ")].filter((x) => x !== "").join(" — "),
    })),
    ...ORIGINAL_CHARACTERS.map((c) => ({ key: c.key, name: c.name, names: [c.name], taken: taken(c.key) })),
  ];
  const candidates = useCandidates(item, facet, true);
  const failed = characters.find((q) => q.isError)?.error?.message ?? dictionary.error?.message ?? candidates.error?.message;

  return (
    <Block label={label}>
      <Chips facet={facet} values={values} item={item} corrections={corrections} />
      <Named type={type} item={item} facet={facet} candidates={candidates.data} universes={universes} corrections={corrections} />
      <div className="flex flex-wrap items-start gap-1">
        <Picker
          label="Добавить персонажа"
          placeholder="Найти персонажа по любому имени или по Q-номеру"
          options={ranked(options, candidates.data)}
          loading={dictionary.isLoading || characters.some((q) => q.isLoading) || candidates.isLoading}
          error={failed}
          empty={universes.length === 0 ? "Работа не связана ни с одной вселенной: есть только оригинальные персонажи" : undefined}
          onPick={(key) => corrections.setFacet(facet, { key }, true)}
          onName={(name) => corrections.setFacet(facet, { name }, true)}
        />
        {allNamed}
      </div>
    </Block>
  );
}

/**
 * The work's pairings; a new one is two of the work's characters. Once the user says there are no
 * others ([complete]), the work's own alone are shown: every other is taken from it, nothing more
 * is offered.
 */
function Pairings({
  type,
  item,
  universes,
  label,
  values,
  complete,
  characters,
  corrections,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  /** The universes the work has. */
  universes: FacetValueInfo[];
  label: string;
  values: FacetValueInfo[];
  complete: boolean;
  /** The characters the work has. */
  characters: FacetValueInfo[];
  corrections: Corrections;
}) {
  const own = values.filter(has);
  const allNamed = own.length > 0 && <AllNamed facet={PAIRINGS} what="пэйринги" others="Других пэйрингов нет" complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <Block label={label}>
        <Chips facet={PAIRINGS} values={own} corrections={corrections} />
        <div>{allNamed}</div>
      </Block>
    );
  return (
    <OpenPairings type={type} item={item} universes={universes} label={label} values={values} characters={characters} corrections={corrections} allNamed={allNamed} />
  );
}

/** The work's pairings while others may be: every value, the candidates the texts name, the picker and a pair of one's own. */
function OpenPairings({
  type,
  item,
  universes,
  label,
  values,
  characters,
  corrections,
  allNamed,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  universes: FacetValueInfo[];
  label: string;
  values: FacetValueInfo[];
  characters: FacetValueInfo[];
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const [adding, setAdding] = useState(false);
  const [a, setA] = useState("");
  const [b, setB] = useState("");
  // The work's characters and the original ones: they pair with anyone, listed among the main ones or not
  const members = [
    ...characters.map((c) => ({ key: c.key, name: c.name })),
    ...ORIGINAL_CHARACTERS.filter((o) => !characters.some((c) => c.key === o.key)),
  ];
  const known = (key: string) => members.some((c) => c.key === key);
  const ready = known(a) && known(b) && a !== b;
  // Every pair of them the work lacks, the likeliest first: the backend pairs a work's characters once it has one
  const candidates = useCandidates(item, PAIRINGS, characters.length > 0);
  const pairs: Option[] = (candidates.data ?? []).map((c) => ({ key: c.key, name: c.name, names: [c.name], taken: false }));

  const add = async () => {
    await corrections.setFacet(PAIRINGS, { key: pairingKey(a, b) }, true);
    setA("");
    setB("");
    setAdding(false);
  };

  const select = (value: string, onChange: (value: string) => void, other: string) => (
    <select value={value} onChange={(e) => onChange(e.target.value)} className={SELECT}>
      <option value="">Персонаж…</option>
      {members.map((c) => (
        <option key={c.key} value={c.key} disabled={c.key === other}>
          {c.name}
        </option>
      ))}
    </select>
  );

  return (
    <Block label={label}>
      <Chips facet={PAIRINGS} values={values} item={item} corrections={corrections} />
      <Named type={type} item={item} facet={PAIRINGS} candidates={candidates.data} universes={universes} corrections={corrections} />
      {characters.length > 0 && (
        <Picker
          label="Выбрать пэйринг"
          placeholder="Найти пару по имени персонажа"
          options={ranked(pairs, candidates.data)}
          loading={candidates.isLoading}
          error={candidates.error?.message}
          empty="Все пары персонажей работы уже отвечены"
          onPick={(key) => corrections.setFacet(PAIRINGS, { key }, true)}
        />
      )}
      {!adding ? (
        <div className="flex flex-wrap items-start gap-1">
          <Button variant="ghost" size="sm" onClick={() => setAdding(true)}>
            <PlusIcon /> Составить пэйринг
          </Button>
          {allNamed}
        </div>
      ) : (
        <div className="flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            Добавить пэйринг: два персонажа работы или ОМП / ОЖП
            <Button variant="ghost" size="icon-xs" onClick={() => setAdding(false)} title="Закрыть">
              <XIcon />
            </Button>
          </div>
          <div className="flex items-center gap-1">
            {select(a, setA, b)}
            <span className="text-muted-foreground">/</span>
            {select(b, setB, a)}
            <AsyncButton size="icon" variant="outline" onClick={add} disabled={!ready} title="Добавить пэйринг" icon={<PlusIcon />} />
          </div>
        </div>
      )}
    </Block>
  );
}

/**
 * A value to pick: its key, its name, a mark after it (the model's chance, the mentions), what the
 * catalogue says it is and a note under it, every name it is found by, and whether the work has it already.
 */
type Option = { key: string; name: string; mark?: string; description?: string; note?: string; names: string[]; taken: boolean };

/** "упоминается 3 раза" — how often the work's texts name a candidate; nothing when they were not counted. */
function mentionsText(c: Candidate): string | undefined {
  if (c.mentions == null) return undefined;
  if (c.mentions === 0) return "не упоминается";
  return `упоминается ${plural(c.mentions, "раз", "раза", "раз")}`;
}

/** "87% · упоминается 3 раза" */
function candidateMark(c: Candidate): string {
  return [c.chance != null ? percent(c.chance) : undefined, mentionsText(c)].filter((x) => x !== undefined).join(" · ");
}

/**
 * The options in the order of the candidates — the likeliest first, then the most named — each
 * with its chance and mentions; those the backend does not offer (the work has them, the user
 * answered on them) after them, in their own order.
 */
function ranked(options: Option[], candidates: Candidate[] | undefined): Option[] {
  if (!candidates) return options;
  const rank = new Map(candidates.map((c, i) => [c.key, i]));
  const byKey = new Map(candidates.map((c) => [c.key, c]));
  return options
    .map((o, i) => ({ o, i, r: rank.get(o.key) ?? candidates.length + i }))
    .sort((x, y) => x.r - y.r)
    .map(({ o }) => {
      const c = byKey.get(o.key);
      return c ? { ...o, mark: candidateMark(c) } : o;
    });
}

/** Of the candidates the texts name, so many are shown: the likeliest. */
const NAMED_SHOWN = 7;

/**
 * The candidates the work's texts name, as chips to confirm or refute — a character the chapters
 * name, two characters named in one paragraph — the likeliest [NAMED_SHOWN] of them; the name
 * opens where the texts name it and why the dictionary has it. Nothing when the texts name none.
 */
function Named({
  type,
  item,
  facet,
  candidates,
  universes,
  corrections,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  facet: string;
  candidates: Candidate[] | undefined;
  /** The universes the work has. */
  universes: FacetValueInfo[];
  corrections: Corrections;
}) {
  const named = (candidates ?? []).filter((c) => (c.mentions ?? 0) > 0);
  if (named.length === 0) return null;
  const shown = named.slice(0, NAMED_SHOWN);
  return (
    <div className="flex flex-col gap-1">
      <div className="text-xs text-muted-foreground">Упоминаются в текстах работы</div>
      <div className="flex flex-wrap items-center gap-1">
        {shown.map((c) => (
          <Badge key={c.key} variant="outline" className="border-dashed pr-0.5 font-normal">
            <MentionsDialog type={type} item={item} facet={facet} value={c.key} name={c.name} url={c.url} summary={mentionsText(c)} universes={universes}>
              {c.name}
              <span className="text-muted-foreground tabular-nums">({candidateMark(c)})</span>
            </MentionsDialog>
            <FacetAnswers facet={facet} value={{ key: c.key }} corrections={corrections} />
          </Badge>
        ))}
        {named.length > shown.length && <span className="text-xs text-muted-foreground">и ещё {named.length - shown.length} в списке ниже</span>}
      </div>
    </div>
  );
}

/**
 * A button that opens a list of values to add, every one of them, narrowed by a line that looks in
 * all their names and finds an id of the catalogue exactly; one the work has already is shown, not
 * offered. With [onName], a typed name is added as a value of the user's own.
 */
function Picker({
  label,
  placeholder,
  options,
  loading,
  error,
  empty,
  onPick,
  onName,
}: {
  label: string;
  placeholder: string;
  options: Option[];
  loading?: boolean;
  error?: string;
  /** What to say when there is nothing to offer. */
  empty?: string;
  onPick: (key: string) => Promise<unknown>;
  onName?: (name: string) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const close = () => {
    setOpen(false);
    setFilter("");
  };

  if (!open)
    return (
      <Button variant="ghost" size="sm" onClick={() => setOpen(true)}>
        <PlusIcon /> {label}
      </Button>
    );

  const needle = filter.trim().toLocaleLowerCase("ru-RU");
  // An id of the catalogue — "Q23991129", or its page's address pasted whole — finds its entry exactly
  const id = needle.split(/[/?#]/).filter((s) => s !== "").pop() ?? "";
  const shown =
    needle === ""
      ? options
      : options.filter((o) => o.key.toLocaleLowerCase("ru-RU").endsWith(`:${id}`) || o.names.some((n) => n.toLocaleLowerCase("ru-RU").includes(needle)));
  const typed = filter.trim();

  return (
    <div className="flex w-full flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        {label}
        <Button variant="ghost" size="icon-xs" onClick={close} title="Закрыть">
          <XIcon />
        </Button>
      </div>
      <div className="flex items-center gap-2">
        <Input
          autoFocus
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && close()}
          placeholder={placeholder}
          className="h-8"
        />
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{needle === "" ? options.length : `${shown.length} из ${options.length}`}</span>
      </div>
      {loading && <Loader2Icon className="size-4 animate-spin text-muted-foreground" />}
      {error && <p className="text-xs text-destructive">Не удалось загрузить: {error}</p>}
      {!loading && options.length === 0 && empty && <p className="text-xs text-muted-foreground">{empty}</p>}
      {shown.length > 0 && (
        <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
          {shown.map((o) => (
            <li key={o.key}>
              <AsyncButton
                variant="ghost"
                onClick={async () => {
                  await onPick(o.key);
                  close();
                }}
                disabled={o.taken}
                title={o.taken ? "Уже у работы" : "Добавить работе"}
                className="h-auto w-full flex-col items-start gap-0 rounded-none px-2 py-1 text-left whitespace-normal"
              >
                <span>
                  {o.name}
                  {o.mark && <span className="ml-1 text-xs font-normal text-muted-foreground tabular-nums">{o.mark}</span>}
                  {o.taken &&<span className="ml-1 text-xs font-normal text-muted-foreground">· уже у работы</span>}
                </span>
                {o.description && <span className="text-xs font-normal">{o.description}</span>}
                {o.note && <span className="text-xs font-normal text-muted-foreground">{o.note}</span>}
              </AsyncButton>
            </li>
          ))}
        </ul>
      )}
      {onName && typed !== "" && (
        <AsyncButton
          variant="outline"
          size="sm"
          className="h-auto self-start whitespace-normal"
          onClick={async () => {
            await onName(typed);
            close();
          }}
          title="Добавить персонажа под этим именем: его нет среди персонажей вселенных работы"
          icon={<PlusIcon />}
        >
          Добавить своё имя «{typed}»
        </AsyncButton>
      )}
    </div>
  );
}
