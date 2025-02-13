import { Module } from "@nestjs/common";
import { TemplatesServiceFluent } from "./template-service-fluent.mjs";
import { TemplatesController } from "./templates-controller.mjs";
import { TemplateService$ } from "./templates-service.mjs";

@Module({
  controllers: [TemplatesController],
  providers: [{ provide: TemplateService$, useClass: TemplatesServiceFluent }],
})
export class TemplatesModule {}
