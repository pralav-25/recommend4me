import "i18next";
import type { Resources } from "./resources";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "common";
    resources: Resources;
    // Flat keys: "Component.purpose" is one key, not a path
    keySeparator: false;
  }
}
