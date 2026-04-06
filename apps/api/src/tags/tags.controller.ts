import { Body, Controller, Get, Post, Query } from "@nestjs/common";
import { ApiBody, ApiCreatedResponse, ApiOkResponse, ApiOperation, ApiQuery, ApiTags } from "@nestjs/swagger";
import { CreateTagRequestDto, ListTagsQueryDto, TagItemDto, TagListResponseDto } from "./tags.dto";
import { TagsService } from "./tags.service";

@ApiTags("tags")
@Controller("api/v1/tags")
export class TagsController {
  constructor(private readonly tagsService: TagsService) {}

  @Get()
  @ApiOperation({ summary: "List available tags" })
  @ApiOkResponse({ type: TagListResponseDto })
  @ApiQuery({ name: "q", required: false, type: String })
  @ApiQuery({ name: "status", required: false, enum: ["active", "archived"] })
  listTags(@Query() query: ListTagsQueryDto) {
    return this.tagsService.listTags(query);
  }

  @Post()
  @ApiOperation({ summary: "Create or upsert a tag" })
  @ApiCreatedResponse({ type: TagItemDto })
  @ApiBody({ type: CreateTagRequestDto })
  createTag(@Body() body: CreateTagRequestDto) {
    return this.tagsService.createTag(body);
  }
}
