import { NestFactory } from "@nestjs/core";
import { DocumentBuilder, SwaggerModule } from "@nestjs/swagger";
import helmet from "helmet";
import { AppModule } from "./app/app-module.mjs";

(async function () {
  const app = await NestFactory.create(AppModule);
  app.setGlobalPrefix("api");

  const config = new DocumentBuilder()
    .setTitle("Fluent API Wrapper")
    .setDescription("The anti corruption layer for fluent")
    .setVersion("1.0")
    .build();

  const document = () => SwaggerModule.createDocument(app, config);
  SwaggerModule.setup("api/docs", app, document);

  app.use(helmet());

  await app.listen(3000);
})();
