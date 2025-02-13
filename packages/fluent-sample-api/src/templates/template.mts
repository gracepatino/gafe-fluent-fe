import { cloneDeep } from "lodash-es";

export interface Template {
  id: string;
  name: string;
}

export class TemplateBuilder {
  private $template: Template;

  public constructor() {
    this.$template = {
      id: "",
      name: "",
    };
  }

  public id(val: string) {
    this.$template.id = val;
    return this;
  }

  public name(val: string) {
    this.$template.name = val;
    return this;
  }

  public build() {
    return cloneDeep(this.$template);
  }
}
