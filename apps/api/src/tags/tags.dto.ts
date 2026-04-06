import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type { CreateTagRequest, TagItem, TagListResponse } from "@xrag/shared-types";
import { IsIn, IsOptional, IsString, MaxLength, MinLength } from "class-validator";

export class CreateTagRequestDto implements CreateTagRequest {
  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  @MaxLength(64)
  name!: string;
}

export class ListTagsQueryDto {
  @ApiPropertyOptional({ type: String })
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional({ type: String, enum: ["active", "archived"] })
  @IsOptional()
  @IsIn(["active", "archived"])
  status?: "active" | "archived";
}

export class TagItemDto implements TagItem {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String })
  name!: string;

  @ApiProperty({ type: String, enum: ["active", "archived"] })
  status!: "active" | "archived";
}

export class TagListResponseDto implements TagListResponse {
  @ApiProperty({ type: () => TagItemDto, isArray: true })
  items!: TagItemDto[];
}
