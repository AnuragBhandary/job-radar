package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.llm.ChatTurn;
import com.anuragbhandary.jobradar.chat.ChatService;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The chat page.
 *
 * <p>History lives in the HTTP session, which is the right amount of durability:
 * a conversation survives navigating away and back, and does not survive a
 * restart. Persisting it would mean storing whatever he typed about salaries and
 * employers in the database forever, for a feature whose value is entirely in the
 * next five minutes.
 */
@Controller
public class ChatController {

    private static final String HISTORY = "job-radar.chat";

    /** Long enough to keep context, short enough that the token bill stays flat. */
    private static final int KEEP_MESSAGES = 24;

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page() {
        if (!chat.isUsable()) {
            return Ui.page("Chat", "", """
                    <section class="card panel">
                      <div class="panel-head"><h2>Assistant</h2></div>
                      <div class="panel-body">
                        <p class="empty">No model configured. Put a key under
                        <code>job-radar.llm</code> in
                        <code>~/.config/job-radar/secrets.yml</code> and restart.</p>
                      </div>
                    </section>
                    """);
        }
        return Ui.page("Chat", "<span class=\"note-muted\">Google Gemini</span>", Ui.chat());
    }

    public record Ask(String message) {
    }

    public record Said(String answer, List<String> actions) {
    }

    @PostMapping(value = "/chat/send", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @SuppressWarnings("unchecked")
    public Said send(@RequestBody Ask ask, HttpSession session) {
        if (ask.message() == null || ask.message().isBlank()) {
            return new Said("", List.of());
        }

        List<ChatTurn.Message> history =
                (List<ChatTurn.Message>) session.getAttribute(HISTORY);
        if (history == null) {
            history = new ArrayList<>();
        }

        ChatService.Answer answer = chat.ask(history, ask.message());

        // Trimmed from the front, keeping the system prompt, so a long session
        // does not resend the whole conversation on every turn.
        List<ChatTurn.Message> kept = new ArrayList<>(answer.history());
        if (kept.size() > KEEP_MESSAGES) {
            ChatTurn.Message system = kept.getFirst();
            kept = new ArrayList<>(kept.subList(kept.size() - KEEP_MESSAGES + 1, kept.size()));
            kept.addFirst(system);
        }
        session.setAttribute(HISTORY, kept);

        return new Said(answer.answer(), answer.actions());
    }

    @PostMapping("/chat/clear")
    public String clear(HttpSession session) {
        session.removeAttribute(HISTORY);
        return "redirect:/chat";
    }
}
