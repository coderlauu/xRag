import { NestFactory } from "@nestjs/core";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import { DocumentBuilder, SwaggerModule } from "@nestjs/swagger";
import cors from "@fastify/cors";
import { AppModule } from "./app.module";

async function bootstrap() {
  const app = await NestFactory.create<NestFastifyApplication>(AppModule, new FastifyAdapter());
  await app.register(cors, {
    origin: true
  });

  const config = new DocumentBuilder()
    .setTitle("xRag API")
    .setDescription("Phase 1A API scaffold")
    .setVersion("0.1.0")
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup("docs", app, document);

  const port = Number(process.env.PORT || 3001);
  await app.listen({ port, host: "0.0.0.0" });
}

void bootstrap();
