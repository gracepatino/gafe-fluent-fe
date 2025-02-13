import { Template } from "./template.mjs";

export const TemplateService$ = Symbol();

export interface TemplatesService {
  list(): Promise<Template[]>;
  get(identification: string): Promise<Template>;
}
