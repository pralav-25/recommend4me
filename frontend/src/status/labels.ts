import i18n from "@/i18n/i18n";

/** The background work of the application the interface names, as `/api/status` names it. */
const WORKS = ["pictures", "texts", "parts", "sets"] as const;

type Work = (typeof WORKS)[number];

function isWork(name: string): name is Work {
  return (WORKS as readonly string[]).includes(name);
}

/** A background work's title and what it does, in the language of the interface; one not known, by its name. */
export function workLabel(name: string): { title: string; about: string } {
  if (!isWork(name)) return { title: name, about: "" };
  return { title: i18n.t(`Work.${name}.title`, { ns: "status" }), about: i18n.t(`Work.${name}.about`, { ns: "status" }) };
}
