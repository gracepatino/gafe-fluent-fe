import { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import request from "supertest";
import { afterEach, describe, expect, it } from "vitest";
import { TemplateBuilder } from "./template.mjs";
import { TemplatesModule } from "./templates-module.mjs";

describe("Templates Service", () => {
  const charmander = new TemplateBuilder()
    .id("charmander")
    .name("Charmander")
    .build();
  const bulbasaur = new TemplateBuilder()
    .id("bulbasaur")
    .name("Bulbasaur")
    .build();
  const templates = [charmander, bulbasaur];

  let $target: INestApplication<any>;

  const getService = async () => {
    const module = await Test.createTestingModule({
      imports: [TemplatesModule],
    }).compile();

    $target = module.createNestApplication();
    await $target.init();
    return $target;
  };

  afterEach(async () => {
    await $target.close();
  });

  describe("List", () => {
    it("should list all of the templates returned from fluent", async () => {
      // Arrange.
      const service = await getService();

      // Act.
      const actual = await request(service.getHttpServer()).get("/templates");

      // Assert.
      expect(actual.status).toEqual(200);
      expect(actual.body).toEqual(templates);
    });
  });

  describe("Get", () => {
    it("should return the specific template from fluent", async () => {
      // Arrange.
      const endpoint = `/templates/${charmander.id}`;
      const service = await getService();

      // Act.
      const actual = await request(service.getHttpServer()).get(endpoint);

      // Assert.
      expect(actual.status).toEqual(200);
      expect(actual.body).toEqual(charmander);
    });
  });
});
