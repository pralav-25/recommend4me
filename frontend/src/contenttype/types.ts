import { useQuery } from "@tanstack/react-query";
import { BookOpenIcon, Gamepad2Icon, LibraryIcon, type LucideIcon } from "lucide-react";
import { api, unwrap, type ContentTypeInfo, type Grade, type SourceInfo } from "@/api/client";

export const ALL_GRADES: Grade[] = [1, 2, 3, 4, 5];

/** The content types with their sources: they change only with the plugins, so they are asked for once. */
export function useTypes() {
  return useQuery({
    queryKey: ["types"],
    queryFn: async () => unwrap(await api.GET("/api/types")),
    staleTime: Infinity,
  });
}

export type SourceOfType = { type: ContentTypeInfo; source: SourceInfo };

export function findSource(types: ContentTypeInfo[] | undefined, sourceId: string): SourceOfType | undefined {
  for (const type of types ?? []) {
    const source = type.sources.find((s) => s.id === sourceId);
    if (source) return { type, source };
  }
  return undefined;
}

/** The content type and the declaration of the source an item comes from. */
export function useSource(sourceId: string | undefined): SourceOfType | undefined {
  const types = useTypes();
  return sourceId === undefined ? undefined : findSource(types.data, sourceId);
}

/** The label of a grade as the content type words it: "Worth a read". */
export function gradeLabel(type: ContentTypeInfo, grade: Grade): string {
  return type.grades[grade - 1] ?? String(grade);
}

export function sourceTitle(type: ContentTypeInfo, sourceId: string): string {
  return type.sources.find((s) => s.id === sourceId)?.title ?? sourceId;
}

/** The icon of a content type: in the tabs, and in place of a missing cover. */
export function typeIcon(typeId: string): LucideIcon {
  switch (typeId) {
    case "games":
      return Gamepad2Icon;
    case "books":
      return BookOpenIcon;
    default:
      return LibraryIcon;
  }
}
