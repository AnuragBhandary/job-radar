package com.anuragbhandary.jobradar.apply.llm;

import java.util.List;
import java.util.Map;

/**
 * One exchange with a tool-calling model.
 *
 * <p>Kept apart from {@link LlmClient#complete} because the two have genuinely
 * different shapes: a completion is a string in and a string out, and a turn is a
 * conversation plus a set of callable tools which may come back asking to run one
 * rather than answering.
 */
public final class ChatTurn {

    private ChatTurn() {
    }

    /**
     * @param role one of system, user, assistant, tool
     * @param toolCallId set on a tool result, naming the call it answers
     */
    public record Message(String role, String content, String toolCallId,
            List<ToolCall> toolCalls) {

        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> calls) {
            return new Message("assistant", content, null, calls);
        }

        public static Message toolResult(String callId, String content) {
            return new Message("tool", content, callId, null);
        }
    }

    /** A model's request to run one tool. */
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * A tool the model may call.
     *
     * @param parameters JSON Schema for the arguments, as a map so it can be
     *                   serialised straight into the request
     */
    public record Tool(String name, String description, Map<String, Object> parameters) {
    }

    /**
     * What came back: either prose, or a request to run tools, occasionally both.
     */
    public record Reply(String content, List<ToolCall> toolCalls) {

        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
