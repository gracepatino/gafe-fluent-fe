import { Injectable, NotImplementedException } from "@nestjs/common";
import { Template } from "./template.mjs";
import { TemplatesService } from "./templates-service.mjs";

@Injectable()
export class TemplatesServiceFluent implements TemplatesService {
  list(): Promise<Template[]> {
    throw new NotImplementedException("Method not implemented.");
  }
  get(): Promise<Template> {
    throw new NotImplementedException("Method not implemented.");
  }
}
