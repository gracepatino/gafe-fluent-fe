import { Controller, Get, Inject, Param } from "@nestjs/common";
import { ApiTags } from "@nestjs/swagger";
import { Template } from "./template.mjs";
import { TemplateService$, TemplatesService } from "./templates-service.mjs";

@ApiTags("Templates")
@Controller("templates")
export class TemplatesController {
  public constructor(
    @Inject(TemplateService$) private $service: TemplatesService,
  ) {}

  @Get()
  public list(): Promise<Template[]> {
    return this.$service.list();
  }

  @Get(":identification")
  public get(
    @Param("identification") identification: string,
  ): Promise<Template> {
    return this.$service.get(identification);
  }
}
