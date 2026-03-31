import { Body, Controller, Get, Post, Query } from "@nestjs/common";
import { ApiCreatedResponse, ApiOkResponse, ApiOperation, ApiTags } from "@nestjs/swagger";
import { CreateTagRequestDto, ListTagsQueryDto, TagItemDto, TagListResponseDto } from "./tags.dto";
import { TagsService } from "./tags.service";

@ApiTags("tags")
@Controller("api/v1/tags")
export class TagsController {
  constructor(private readonly tagsService: TagsService) {}

  @Get()
  @ApiOperation({ summary: "List available tags" })
  @ApiOkResponse({ type: TagListResponseDto })
  listTags(@Query() query: ListTagsQueryDto) {
    return this.tagsService.listTags(query);
  }

  @Post()
  @ApiOperation({ summary: "Create or upsert a tag" })
  @ApiCreatedResponse({ type: TagItemDto })
  createTag(@Body() body: CreateTagRequestDto) {
    return this.tagsService.createTag(body);
  }
}
