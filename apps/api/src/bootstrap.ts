import { ValidationPipe } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import { FastifyAdapter, type NestFastifyApplication } from "@nestjs/platform-fastify";
import cors from "@fastify/cors";
import { loadApiEnv } from "./config/env";
import { AppModule } from "./app.module";

export async function createApp() {
  const app = await NestFactory.create<NestFastifyApplication>(AppModule, new FastifyAdapter());
  await app.register(cors, {
    origin: true
  });

  app.useGlobalPipes(
    new ValidationPipe({
      transform: true,
      whitelist: true,
      transformOptions: {
        enableImplicitConversion: true
      }
    })
  );

  return app;
}

export async function bootstrap() {
  const env = loadApiEnv();
  const app = await createApp();
  await app.listen({ port: env.port, host: "0.0.0.0" });
}
