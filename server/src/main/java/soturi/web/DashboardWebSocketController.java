package soturi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import soturi.model.messages_to_client.EnemiesAppear;
import soturi.model.messages_to_client.EnemiesDisappear;
import soturi.model.messages_to_client.MessageToClient;
import soturi.model.messages_to_client.MessageToClientFactory;
import soturi.server.GameService;

import java.util.function.Consumer;

@Slf4j
@AllArgsConstructor
@Component
public class DashboardWebSocketController extends AbstractWebSocketHandler {
    private final ObjectMapper mapper;
    private final GameService gameService;

    private MessageToClientFactory sendToSession(WebSocketSession session) {
        return new MessageToClientFactory(new Consumer<>() {
            @Override
            @SneakyThrows
            public void accept(MessageToClient messageToClient) {
                if (messageToClient instanceof EnemiesAppear appear)
                    log.info("[DASH] EnemiesAppear[#={}]", appear.enemies().size());
                else if (messageToClient instanceof EnemiesDisappear disappear)
                    log.info("[DASH] EnemiesDisappear[#={}]", disappear.enemyIds().size());
                else
                    log.info("[DASH] {}", messageToClient);

                String asText = mapper.writeValueAsString(messageToClient);
                session.sendMessage(new TextMessage(asText));
            }
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[DASH] {} Opened", session.getId());
        gameService.addObserver(session.getId(), sendToSession(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[DASH] {} Closed [STATUS] {}", session.getId(), status);
        gameService.removeObserver(session.getId());
    }
}
