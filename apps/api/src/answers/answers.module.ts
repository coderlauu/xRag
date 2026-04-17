import { Module } from "@nestjs/common";
import { AnswersController } from "./answers.controller";
import { AnswersRepository } from "./answers.repository";
import { AnswersService } from "./answers.service";

@Module({
  controllers: [AnswersController],
  providers: [AnswersRepository, AnswersService],
  exports: [AnswersRepository, AnswersService]
})
export class AnswersModule {}
