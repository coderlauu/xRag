package com.app.qa.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.model.ServiceTier;
import com.app.ai.model.UserMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QaDomainVocabularyTests {

    @Test
    void 业务ID拒绝空值并保持各自类型() {
        var value = UUID.fromString("00000000-0000-0000-0000-000000000042");

        assertThat(new ConversationId(value).value()).isEqualTo(value);
        assertThat(new TurnId(value).value()).isEqualTo(value);
        assertThat(new MessageId(value).value()).isEqualTo(value);
        assertThat(new SubQuestionId(value).value()).isEqualTo(value);
        assertThat(new RetrievalActionId(value).value()).isEqualTo(value);
        assertThatThrownBy(() -> new ConversationId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void Java闭集与3A数据库契约一致且不提前加入答案() {
        assertThat(TurnStatus.values()).extracting(Enum::name).containsExactly(
                "SAVED", "PROCESSING", "ACTIONS_READY", "WAITING_CLARIFICATION",
                "REJECTED_TOO_MANY_TOPICS", "FAILED");
        assertThat(MessageType.values()).extracting(Enum::name).containsExactly(
                "USER_QUERY", "CLARIFICATION_CHOICE", "CLARIFICATION_GUIDANCE", "SYSTEM_NOTICE");
        assertThat(RetrievalActionType.values()).extracting(Enum::name).containsExactly(
                "KB_TARGETED", "GLOBAL_FALLBACK", "SYSTEM", "NO_MATCH");
        assertThat(SystemAction.values()).extracting(Enum::name).containsExactly(
                "GREETING", "ASSISTANT_IDENTITY", "CAPABILITY_HELP");
    }

    @Test
    void 问答模型命令固定使用Balanced且不携带采样参数() {
        var command = QaChatCommandFactory.balanced(List.of(
                new UserMessage("问题")));

        assertThat(command.tier()).isEqualTo(ServiceTier.BALANCED);
        assertThat(command.tools()).isEmpty();
        assertThat(command.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("temperature", "topP");
    }
}
