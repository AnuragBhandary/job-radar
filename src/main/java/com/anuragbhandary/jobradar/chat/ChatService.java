package com.anuragbhandary.jobradar.chat;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.llm.ChatTurn;
import com.anuragbhandary.jobradar.apply.llm.HumanTone;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The chat loop: ask, run whatever tools the model wants, ask again.
 *
 * <p>Capped at {@link #MAX_ROUNDS} tool rounds. A model that has not answered
 * after four passes over a database of nine thousand postings is not converging,
 * and an uncapped loop is a way to spend a free tier in an afternoon.
 *
 * <p>Conversation state lives in the caller's session rather than here. The
 * service is stateless so a restart loses a chat and nothing else.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_ROUNDS = 4;

    private final LlmClient llm;
    private final ChatTools tools;
    private final ApplicantProfile profile;

    public ChatService(LlmClient llm, ChatTools tools, ApplicantProfile profile) {
        this.llm = llm;
        this.tools = tools;
        this.profile = profile;
    }

    public boolean isUsable() {
        return llm.isUsable();
    }

    /**
     * @param answer  what to show, already tone-cleaned
     * @param actions a line per tool that changed something, so the page can say
     *                what happened rather than leaving the user to notice
     */
    public record Answer(String answer, List<String> actions, List<ChatTurn.Message> history) {
    }

    public Answer ask(List<ChatTurn.Message> history, String question) {
        List<ChatTurn.Message> conversation = new ArrayList<>();
        if (history.isEmpty()) {
            conversation.add(ChatTurn.Message.system(systemPrompt()));
        } else {
            conversation.addAll(history);
        }
        conversation.add(ChatTurn.Message.user(question));

        List<String> actions = new ArrayList<>();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            Optional<ChatTurn.Reply> reply = llm.chat(conversation, tools.definitions());
            if (reply.isEmpty()) {
                String why = llm.lastFailure();
                return new Answer(why == null
                        ? "The model did not answer. Try again."
                        : why, actions, conversation);
            }

            ChatTurn.Reply turn = reply.get();
            if (!turn.wantsTools()) {
                String text = HumanTone.removeDashes(turn.content());
                conversation.add(ChatTurn.Message.assistant(text, null));
                return new Answer(text, actions, conversation);
            }

            conversation.add(ChatTurn.Message.assistant(turn.content(), turn.toolCalls()));
            for (ChatTurn.ToolCall call : turn.toolCalls()) {
                log.info("chat tool: {} {}", call.name(), call.argumentsJson());
                String result = tools.run(call.name(), call.argumentsJson());
                conversation.add(ChatTurn.Message.toolResult(call.id(), result));

                // Only the two writes are worth telling the user about. Reads are
                // how it answers and narrating them is noise.
                if (call.name().equals("save_job") || call.name().equals("move_job")) {
                    actions.add(describe(call.name(), result));
                }
            }
        }

        return new Answer("I went round four times without settling on an answer. "
                + "Try asking something narrower.", actions, conversation);
    }

    private static String describe(String tool, String result) {
        return tool.equals("save_job")
                ? "saved a job to the board" : "moved a job on the board";
    }

    /**
     * The brief.
     *
     * <p>Long, and every paragraph is there because the alternative is worse.
     * A model given a job database and no context will cheerfully recommend a
     * senior role in a country he cannot work in, quote a salary in the wrong
     * currency, and tell him his experience is stronger than it is.
     *
     * <p>The paragraph that supplies that context is not written here. It comes
     * from {@code assistantBriefing} in the gitignored profile, because it is the
     * most personal writing in the project - what a year of experience is really
     * worth, what a salary has to clear - and this repository is public.
     */
    private String systemPrompt() {
        List<String> briefing = profile.assistantBriefing();
        String about = briefing.isEmpty()
                ? "You have not been told anything about him beyond the profile, so "
                        + "call profile_summary before any advice that depends on what "
                        + "he can claim, and ask rather than assume.\n"
                : "What you know about him comes from profile_summary. Call it before "
                        + "any advice that depends on what he can claim. The short "
                        + "version, and you should not contradict it:\n\n"
                        + briefing.stream().map(line -> "                - " + line)
                                .collect(java.util.stream.Collectors.joining("\n"))
                        + "\n";

        return """
                You are the assistant inside job-radar, a tool one person runs on
                their own laptop to find and apply for backend engineering jobs.
                You are talking to that person. Call the tools rather than guessing:
                the database has thousands of postings and you cannot see any of it
                until you ask.

                %s
                The match score is arithmetic, not judgement: skills 40, experience
                25, geography 20, freshness 10, signals 5. Explain it when it comes
                up rather than treating it as an oracle, and say when you disagree
                with it.

                You can bookmark a job and move a card between columns. You cannot
                submit an application and must not offer to: most boards accept one
                application per posting forever. Preparing one takes a minute and
                opens a browser, so point him at the Prepare button rather than
                trying.

                %s
                Be brief. Answer the question asked. When you recommend jobs, give
                the id, the company, the role and one specific reason, and say what
                is wrong with them as well as what is right.
                """.formatted(about, HumanTone.styleRules());
    }
}
