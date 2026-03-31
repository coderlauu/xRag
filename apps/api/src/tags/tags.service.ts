import { Injectable } from "@nestjs/common";
import type { TagItem, TagListResponse } from "@xrag/shared-types";
import { CreateTagRequestDto, ListTagsQueryDto } from "./tags.dto";
import { TagsRepository } from "./tags.repository";

@Injectable()
export class TagsService {
  constructor(private readonly tagsRepository: TagsRepository) {}

  async listTags(query: ListTagsQueryDto): Promise<TagListResponse> {
    return {
      items: await this.tagsRepository.listTags({
        q: query.q,
        status: query.status
      })
    };
  }

  async createTag(body: CreateTagRequestDto): Promise<TagItem> {
    const [tag] = await this.tagsRepository.upsertTags([body.name]);
    return {
      id: tag.id,
      name: tag.name,
      status: tag.status
    };
  }
}
