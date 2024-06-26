package soturi.server.communication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import soturi.common.VersionInfo;
import soturi.model.Position;
import soturi.model.messages_to_client.Disconnect;
import soturi.model.messages_to_client.EnemiesAppear;
import soturi.model.messages_to_client.EnemiesDisappear;
import soturi.model.messages_to_client.MessageToClient;
import soturi.model.messages_to_client.MessageToClientFactory;
import soturi.model.messages_to_client.MessageToClientHandler;
import soturi.model.messages_to_client.Ping;
import soturi.model.messages_to_server.MessageToServer;
import soturi.server.GameService;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class is thread safe
 */
@Slf4j
public class Connection {
    private final WebSocketSession session;
    private final GameService gameService;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<MessageToClient> queue = new LinkedBlockingQueue<>();

    private volatile String authorizedUser = null;
    private volatile boolean closed = false;

    private volatile Instant lastPing = Instant.now();
    private volatile Instant lastReceived = Instant.now();

    private static Position positionFromStrings(String latitude, String longitude) {
        try {
            double latitudeAsDouble = Double.parseDouble(latitude);
            double longitudeAsDouble = Double.parseDouble(longitude);
            return new Position(latitudeAsDouble, longitudeAsDouble);
        }
        catch (Exception exception) {
            return null;
        }
    }
    private static boolean isOutdated(String compilationTime) {
        LocalDateTime userTime = LocalDateTime.MIN;
        LocalDateTime serverTime = VersionInfo.appCompilationTime().orElse(LocalDateTime.MIN);

        try {
            userTime = LocalDateTime.parse(compilationTime);
        }
        catch (Exception ignored) { }

        return userTime.isBefore(serverTime);
    }

    public Connection(WebSocketSession session, GameService gameService, ObjectMapper objectMapper) {
        synchronized (session) {
            this.session = session;
            this.gameService = gameService;
            this.objectMapper = objectMapper;

            HttpHeaders headers = session.getHandshakeHeaders();

            String user = headers.getFirst("epic-name");
            String password = headers.getFirst("epic-password");
            String latitude = headers.getFirst("epic-latitude");
            String longitude = headers.getFirst("epic-longitude");
            String compilationTime = headers.getFirst("epic-version");

            Position position = positionFromStrings(latitude, longitude);
            MessageToClientHandler handler = new MessageToClientFactory(queue::add);

            if (gameService.login(user, password, position, handler))
                authorizedUser = user;
            else
                scheduleToClose();

            Thread.ofVirtual().start(this::work);

            if (isOutdated(compilationTime))
                handler.error("Your app is outdated, download new version from https://soturi.online/static/app.apk");
        }
    }

    public void scheduleToClose() {
        queue.add(new Disconnect());
    }

    public void close() {
        synchronized (session) {
            if (closed)
                return;
            closed = true;
            try {
                session.close();
            }
            catch (IOException exception) {
                log.error("WebSocketSession::close() can throw !?", exception);
            }
            if (authorizedUser != null)
                gameService.logout(authorizedUser);
        }
    }

    public void handleTextMessage(String message) {
        synchronized (session) {
            if (closed)
                return;
            if (authorizedUser == null) {
                scheduleToClose();
                return;
            }
            lastReceived = Instant.now();
            try {
                MessageToServer messageToServer = objectMapper.readValue(message, MessageToServer.class);
                log.info("[FROM] {} [MSG] {}", authorizedUser, messageToServer);
                messageToServer.process(gameService.receiveFrom(authorizedUser));
            }
            catch (JsonProcessingException jsonProcessingException) {
                log.error("user thinks he is funny", jsonProcessingException);
                close();
            }
        }
    }

    private void doSendMessage(MessageToClient messageToClient) {
        synchronized (session) {
            if (closed || messageToClient instanceof Disconnect) {
                close();
                return;
            }

            if (messageToClient instanceof Ping)
                lastPing = Instant.now();

            if (messageToClient instanceof EnemiesAppear appear)
                log.info("[ TO ] {} [MSG] EnemiesAppear[#={}]", authorizedUser, appear.enemies().size());
            else if (messageToClient instanceof EnemiesDisappear disappear)
                log.info("[ TO ] {} [MSG] EnemiesDisappear[#={}]", authorizedUser, disappear.enemyIds().size());
            else
                log.info("[ TO ] {} [MSG] {}", authorizedUser, messageToClient);

            try {
                String payload = objectMapper.writeValueAsString(messageToClient);
                session.sendMessage(new TextMessage(payload));
            }
            catch (JsonProcessingException jsonProcessingException) {
                log.error("this should not happen", jsonProcessingException);
                close();
            }
            catch (IOException | IllegalStateException exception) {
                close();
            }
        }
    }

    private void workOnce() {
        MessageToClient messageToClient;
        try {
            messageToClient = BlockingQueuePoll.poll(queue, 2);
        }
        catch (InterruptedException interruptedException) {
            log.error("this should not happen", interruptedException);
            close();
            return;
        }
        synchronized (session) {
            long millisSinceLastReceived = Duration.between(lastReceived, Instant.now()).toMillis();
            long millisSinceLastPing = Duration.between(lastPing, Instant.now()).toMillis();

            if (millisSinceLastReceived > 8000) {
                close();
                return;
            }
            if (millisSinceLastReceived > 2000 && millisSinceLastPing > 3000)
                doSendMessage(new Ping());

            if (messageToClient != null)
                doSendMessage(messageToClient);
        }
    }

    private void work() {
        while (!closed)
            workOnce();
    }
}
