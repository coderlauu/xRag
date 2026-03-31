import { Body, Controller, Get, Post, Query } from "@nestjs/common";
import type { CreateTagRequest, TagItem, TagListResponse } from "@xrag/shared-types";
import { sampleTags } from "../common/sample-data";

@Controller("api/v1/tags")
export class TagsController {
  @Get()
  listTags(@Query("q") query = ""): TagListResponse {
    return {
      items: sampleTags.items.filter((item) => {
        if (!query) {
          return true;
        }

        return item.name.toLowerCase().includes(query.toLowerCase());
      })
    };
  }

  @Post()
  createTag(@Body() body: CreateTagRequest): TagItem {
    return {
      id: "tag_new",
      name: body.name,
      status: "active"
    };
  }
}
