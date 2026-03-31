import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type { CreateTagRequest, TagItem, TagListResponse } from "@xrag/shared-types";
import { IsIn, IsOptional, IsString, MaxLength, MinLength } from "class-validator";

export class CreateTagRequestDto implements CreateTagRequest {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  @MaxLength(64)
  name!: string;
}

export class ListTagsQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional({ enum: ["active", "archived"] })
  @IsOptional()
  @IsIn(["active", "archived"])
  status?: "active" | "archived";
}

export class TagItemDto implements TagItem {
  @ApiProperty()
  id!: string;

  @ApiProperty()
  name!: string;

  @ApiProperty({ enum: ["active", "archived"] })
  status!: "active" | "archived";
}

export class TagListResponseDto implements TagListResponse {
  @ApiProperty({ type: () => TagItemDto, isArray: true })
  items!: TagItemDto[];
}
