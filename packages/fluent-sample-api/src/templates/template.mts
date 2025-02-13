import { cloneDeep } from "lodash-es";

export interface Template {
  id: string;
  name: string;
}

export class TemplateBuilder {
  private _template: Template;

  public constructor() {
    this._template = {
      id: "",
      name: "",
    };
  }

  public build() {
    return cloneDeep(this._template);
  }
}
