import { Module } from "@nestjs/common";
import { TemplatesModule } from "../templates/templates-module.mjs";

@Module({
  imports: [TemplatesModule],
})
export class AppModule {}
