package com.app.qa.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.qa.model.IntentNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntentCatalogEncoderTests {

    @Test
    void 候选按UUID稳定排序且相同集合产生字节相同JSON() {
        var encoder = new IntentCatalogEncoder(new ObjectMapper());
        var later = new IntentCatalogEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                IntentNodeType.KB,
                "业务 › 售后",
                "售后政策",
                List.of("如何退货"));
        var earlier = new IntentCatalogEntry(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                IntentNodeType.SYSTEM,
                "系统 › 身份",
                "询问助手身份",
                List.of("你是谁"));

        String first = encoder.encode(List.of(later, earlier));
        String second = encoder.encode(List.of(earlier, later));

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("[{\"id\":\"00000000-0000-0000-0000-000000000010\","
                + "\"type\":\"SYSTEM\",\"fullPath\":\"系统 › 身份\",\"description\":\"询问助手身份\","
                + "\"examples\":[\"你是谁\"]},{\"id\":\"00000000-0000-0000-0000-000000000020\","
                + "\"type\":\"KB\",\"fullPath\":\"业务 › 售后\",\"description\":\"售后政策\","
                + "\"examples\":[\"如何退货\"]}]");
    }
}
