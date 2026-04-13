import { Body, Controller, Get, HttpCode, Param, Post, Query } from "@nestjs/common";
import { ApiAcceptedResponse, ApiBody, ApiOkResponse, ApiOperation, ApiParam, ApiTags } from "@nestjs/swagger";
import {
  AnswerRetrievalTraceResponseDto,
  ListAnswerSessionsQueryDto,
  ListAnswerSessionsResponseDto,
  AnswerSessionResponseDto,
  CreateAnswerRequestDto,
  CreateAnswerResponseDto
} from "./answers.dto";
import { AnswersService } from "./answers.service";

@ApiTags("answers")
@Controller("api/v1/answers")
export class AnswersController {
  constructor(private readonly answersService: AnswersService) {}

  @Get()
  @ApiOperation({ summary: "List recent answer sessions" })
  @ApiOkResponse({ type: ListAnswerSessionsResponseDto })
  listAnswers(@Query() query: ListAnswerSessionsQueryDto) {
    return this.answersService.listAnswers(query);
  }

  @Post()
  @HttpCode(202)
  @ApiOperation({ summary: "Create an answer session and enqueue retrieval/synthesis" })
  @ApiBody({ type: CreateAnswerRequestDto })
  @ApiAcceptedResponse({ type: CreateAnswerResponseDto })
  createAnswer(@Body() body: CreateAnswerRequestDto) {
    return this.answersService.createAnswer(body);
  }

  @Get(":sessionId")
  @ApiOperation({ summary: "Get answer session status and answer payload" })
  @ApiOkResponse({ type: AnswerSessionResponseDto })
  @ApiParam({ name: "sessionId", type: String })
  getAnswer(@Param("sessionId") sessionId: string) {
    return this.answersService.getAnswer(sessionId);
  }

  @Get(":sessionId/retrieval")
  @ApiOperation({ summary: "Get retrieval trace for an answer session" })
  @ApiOkResponse({ type: AnswerRetrievalTraceResponseDto })
  @ApiParam({ name: "sessionId", type: String })
  getAnswerRetrieval(@Param("sessionId") sessionId: string) {
    return this.answersService.getAnswerRetrieval(sessionId);
  }
}
