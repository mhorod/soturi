package soturi.server;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import soturi.common.Registry;
import soturi.common.VersionInfo;
import soturi.model.Config;
import soturi.model.Enemy;
import soturi.model.EnemyId;
import soturi.model.EnemyType;
import soturi.model.FightRecord;
import soturi.model.FightResult;
import soturi.model.ItemId;
import soturi.model.Player;
import soturi.model.PlayerWithPosition;
import soturi.model.PolygonWithDifficulty;
import soturi.model.Position;
import soturi.model.QuestStatus;
import soturi.model.Result;
import soturi.model.Reward;
import soturi.model.Statistics;
import soturi.model.messages_to_client.MessageToClientHandler;
import soturi.model.messages_to_server.MessageToServerFactory;
import soturi.model.messages_to_server.MessageToServerHandler;
import soturi.server.communication.MessageToClientCacheSplitLayer;
import soturi.server.database.FightEntity;
import soturi.server.database.FightRepository;
import soturi.server.database.PlayerEntity;
import soturi.server.database.PlayerRepository;
import soturi.server.geo.CityProvider;
import soturi.server.geo.MonsterManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

@Slf4j
@Component
public class GameService {
    private final PlayerRepository repository;
    private final FightRepository fightRepository;
    private final DynamicConfig dynamicConfig;
    private final CityProvider cityProvider;

    private volatile Registry registry;
    private volatile MonsterManager monsterManager;

    public GameService(PlayerRepository repository, FightRepository fightRepository, DynamicConfig dynamicConfig, CityProvider cityProvider) {
        log.info("Compilation time: {}", VersionInfo.compilationTime);
        log.info("Commit id: {}", VersionInfo.commitId);

        this.repository = repository;
        this.fightRepository = fightRepository;
        this.dynamicConfig = dynamicConfig;
        this.cityProvider = cityProvider;
        registry = dynamicConfig.getRegistry();
        monsterManager = new MonsterManager(cityProvider, registry, this::nextEnemyId);
    }

    private final Map<String, PlayerSession> sessions = new LinkedHashMap<>();
    private final Map<String, MessageToClientHandler> observers = new LinkedHashMap<>();

    private volatile Instant questsDeadline = Instant.now();
    private final Map<String, List<QuestStatus>> playerQuests = new LinkedHashMap<>();

    public void kickAllPlayers() {
        while (!sessions.isEmpty())
            logout(sessions.entrySet().iterator().next().getKey());
    }

    public void kickAllObservers() {
        while (!observers.isEmpty())
            removeObserver(observers.entrySet().iterator().next().getKey());
    }

    public synchronized void unregisterAllEnemies() {
        unregisterEnemies(getEnemies().stream().map(Enemy::enemyId).toList());
    }

    public synchronized void setConfig(Config config) {
        dynamicConfig.setConfigWithoutReloading(config);
        unregisterAllEnemies();
        registry = dynamicConfig.getRegistry();
        monsterManager = new MonsterManager(cityProvider, registry, this::nextEnemyId);

        for (PlayerSession session : sessions.values())
            session.sender.setConfig(config);
        for (MessageToClientHandler obs : observers.values())
            obs.setConfig(config);

        clearQuests();
    }

    public synchronized List<Enemy> getEnemies() {
        return monsterManager.getAllEnemies();
    }
    public synchronized long getEnemyCount() {
        return monsterManager.getEnemyCount();
    }

    public synchronized List<PolygonWithDifficulty> getAreas() {
        return monsterManager.getAreas();
    }

    public synchronized List<PlayerWithPosition> getPlayers() {
        return sessions.values().stream().map(PlayerSession::toPlayerWithPosition).toList();
    }

    public synchronized List<Player> getRegisteredPlayers() {
        return repository
            .findAll()
            .stream()
            .sorted(Comparator.comparingLong(PlayerEntity::getXp))
            .map(this::getPlayerFromEntity)
            .toList();
    }

    public synchronized void healPlayers() {
        for (PlayerSession session : sessions.values()) {
            Player me = session.toPlayer();

            long missingHp = me.statistics().maxHp() - me.hp();
            long healed = (long) (missingHp * registry.getHealFraction()) + 1;
            healed = Math.max(0, Math.min(missingHp, healed));
            if (healed == 0)
                continue;
            session.applyAddHp(healed);
            session.sendUpdates();
        }
    }

    private volatile long secondCount = 0;
    private synchronized void doTickEverySecond() {
        secondCount++;

        int fxDelay = registry.getGiveFreeXpDelayInSeconds();
        if (fxDelay > 0 && secondCount % fxDelay == 0)
            giveFreeXp();

        int seDelay = registry.getSpawnEnemyDelayInSeconds();
        if (seDelay > 0 && secondCount % seDelay == 0)
            spawnEnemies();

        int hpDelay = registry.getHealDelayInSeconds();
        if (hpDelay > 0 && secondCount % hpDelay == 0)
            healPlayers();

        if (Instant.now().isAfter(questsDeadline))
            clearQuests();
    }

    private volatile boolean doTick = true;
    @Scheduled(fixedDelay = 1000)
    private synchronized void tickEverySecond() {
        if (doTick)
            doTickEverySecond();
    }

    public synchronized void setDoTick(boolean doTick) {
        this.doTick = doTick;
    }

    private synchronized void giveFreeXp() {
        log.info("giveFreeXp() called");

        Reward reward = new Reward(registry.getGiveFreeXpAmount());
        if (reward.xp() == 0)
            return;
        for (PlayerSession session : sessions.values()) {
            session.applyReward(reward);
            session.sendUpdates();
        }
    }

    public synchronized void clearQuests() {
        log.info("clearQuests()");

        playerQuests.clear();

        long questDuration = registry.getQuestDurationInSeconds();
        long approxDeadline = Instant.now().getEpochSecond() + questDuration * 3 / 2;
        questsDeadline = Instant.ofEpochSecond(approxDeadline / questDuration * questDuration);
        sessions.values().forEach(PlayerSession::sendUpdates);
    }

    public synchronized void setQuests(String playerName, List<QuestStatus> quests) {
        playerQuests.put(playerName, new ArrayList<>(quests));
    }

    private long nextEnemyIdLong = 0;
    public EnemyId nextEnemyId() {
        return new EnemyId(nextEnemyIdLong++);
    }

    private synchronized void spawnEnemies() {
        Instant start = Instant.now();
        List<Enemy> generated = monsterManager.generateEnemies();
        registerEnemies(generated);
        long ms = Duration.between(start, Instant.now()).toMillis();

        log.info("spawnEnemies() took {}ms, generated {} enemies", ms, generated.size());
    }

    public synchronized void registerEnemy(Enemy enemy) {
        registerEnemies(List.of(enemy));
    }

    public synchronized void registerEnemies(List<Enemy> enemies) {
        if (enemies.isEmpty())
            return;

        enemies.forEach(monsterManager::registerEnemy);

        for (var session : sessions.values())
            session.enemiesAppear(enemies);
        for (var sender : observers.values())
            sender.enemiesAppear(enemies);
    }

    private synchronized void unregisterEnemy(EnemyId enemyId) {
        unregisterEnemies(List.of(enemyId));
    }

    private synchronized void unregisterEnemies(List<EnemyId> enemyIds) {
        if (enemyIds.isEmpty())
            return;

        enemyIds.forEach(monsterManager::unregisterEnemy);

        for (var session : sessions.values())
            session.enemiesDisappear(enemyIds);
        for (var sender : observers.values())
            sender.enemiesDisappear(enemyIds);
    }

    public synchronized Player getPlayerFromEntity(PlayerEntity entity) {
        int lvl = registry.getLvlFromXp(entity.getXp());

        List<ItemId> equipped = entity.getEquipped().stream().map(ItemId::new).toList();
        List<ItemId> inventory = entity.getInventory().stream().map(ItemId::new).toList();

        Statistics stats = registry.getPlayerStatistics(lvl);
        for (ItemId itemId : equipped)
            stats = stats.add(registry.getItemById(itemId).statistics());

        return new Player(
            entity.getName(),
            lvl,
            entity.getXp(),
            entity.getHp(),
            stats,
            equipped,
            inventory
        );
    }

    public class PlayerSession implements MessageToServerHandler {
        public final MessageToClientHandler sender;
        public final String playerName;
        public Position position, looking;
        private PlayerEntity playerEntity;

        public PlayerSession(MessageToClientHandler sender, String playerName, Position position, Position looking) {
            this.sender = sender;
            this.playerName = playerName;
            this.position = position;
            this.looking = looking;
            playerEntity = repository.findByName(playerName).orElseThrow();
        }

        private List<QuestStatus> generateQuests() {
            Player me = toPlayer();
            Random rnd = new Random();

            EnemyType enemyType = registry.getRandomEnemyTypeOfLvl(me.lvl());
            int cntEnemyType = rnd.nextInt(5, 11);

            int cntEnemy = rnd.nextInt(10, 21);

            List<Reward> rewards = new ArrayList<>(List.of(
                new Reward(rnd.nextInt(5000)),
                new Reward(rnd.nextInt(2000)),
                new Reward(List.of(registry.getRandomElement(registry.getAllItems()).itemId()))
            ));
            Collections.shuffle(rewards);

            List<QuestStatus> list = new ArrayList<>(List.of(
                new QuestStatus("Beat %d enemies of type %s".formatted(cntEnemyType, enemyType.name()), 0, cntEnemyType, rewards.get(0)),
                new QuestStatus("Beat %d enemies".formatted(cntEnemy), 0, cntEnemy, rewards.get(1)),
                new QuestStatus("Gain new lvl", 0, 1, rewards.get(2))
            ));

            Collections.shuffle(list);
            return list;
        }

        public List<QuestStatus> getQuestsStatuses() {
            return playerQuests.computeIfAbsent(playerName, _name -> generateQuests());
        }

        private void updateQuests(Function<QuestStatus, Long> visitor) {
            List<QuestStatus> list = getQuestsStatuses();
            for (int i = 0; i < list.size(); ++i) {
                QuestStatus status = list.get(i);
                if (status.isFinished())
                    continue;
                long newProgress = status.progress() + visitor.apply(status);
                newProgress = Math.max(0, Math.min(newProgress, status.goal()));

                QuestStatus newStatus = status.withProgress(newProgress);
                list.set(i, newStatus);
                if (newStatus.isFinished())
                    applyReward(newStatus.reward());
            }
        }

        private final Set<EnemyId> visibleEnemies = new HashSet<>();
        private Position lastFullUpdate;
        private void sendUpdates() {
            playerEntity = repository.save(playerEntity);

            Player me = toPlayer();

            sender.meUpdate(me);
            sender.questUpdate(questsDeadline, getQuestsStatuses());
            for (var sender : observers.values())
                sender.playerUpdate(me, position);

            if (lastFullUpdate == null || lastFullUpdate.distance(position) > registry.getVisibilityRefreshRangeInMeters()) {
                lastFullUpdate = position;
                enemiesAppear(getEnemies());
            }
        }

        public void enemiesDisappear(List<EnemyId> enemyIds) {
            enemyIds = enemyIds.stream().filter(visibleEnemies::remove).toList();
            sender.enemiesDisappear(enemyIds);
        }

        public void enemiesAppear(List<Enemy> enemies) {
            enemies = enemies.stream()
                .filter(e -> e.position().distance(position) < registry.getVisibilityRangeInMeters())
                .filter(e -> visibleEnemies.add(e.enemyId()))
                .toList();
            sender.enemiesAppear(enemies);
        }

        public void applySetEquipment(List<ItemId> equipped, List<ItemId> inventory) {
            playerEntity.setEquipped(equipped.stream().map(ItemId::id).toList());
            playerEntity.setInventory(inventory.stream().map(ItemId::id).toList());
        }

        public void applyReward(Reward reward) {
            applyAddXp(reward.xp());

            // todo used applySetEquipment
            List<Long> inventory = Stream.concat(
                playerEntity.getInventory().stream(),
                reward.items().stream().map(ItemId::id)
            ).toList();

            playerEntity.setInventory(inventory);
        }

        public void applyAddXp(long xp) {
            long lvlBefore = registry.getLvlFromXp(playerEntity.getXp());
            playerEntity.setXp(playerEntity.getXp() + xp);
            long lvlAfter = registry.getLvlFromXp(playerEntity.getXp());
            updateQuests(s -> {
                if (!s.quest().equals("Gain new lvl"))
                    return 0L;
                return lvlAfter - lvlBefore;
            });
        }

        public void applyAddHp(long hp) {
            playerEntity.setHp(playerEntity.getHp() + hp);
        }

        public void applyFightResult(FightResult result) {
            applyReward(result.reward());
            applyAddHp(-result.lostHp());
        }

        @Override
        public void attackEnemy(EnemyId enemyId) {
            Player me = toPlayer();

            if (me.hp() <= 0) {
                sender.error("You do not have any hp");
                return;
            }

            Enemy enemy = monsterManager.getEnemyMap().get(enemyId);
            if (enemy == null) {
                sender.error("this enemy does not exist");
                return;
            }
            if (enemy.position().distance(position) > registry.getFightingDistanceMaxInMeters()) {
                sender.error("this enemy is too far");
                return;
            }

            FightResult result = new FightSimulator(registry).simulateFight(me, enemy);

            if (result.result() == Result.WON) {
                unregisterEnemy(enemyId);

                EnemyType type = registry.getEnemyType(enemy);
                updateQuests(s -> {
                    if (s.quest().endsWith("enemies") || s.quest().endsWith(type.name()))
                        return 1L;
                    return 0L;
                });
            }

            sender.fightInfo(enemy.enemyId(), result);
            applyFightResult(result);
            sendUpdates();

            FightRecord fightRecord = new FightRecord(
                new PlayerWithPosition(me, position),
                enemy,
                result,
                Instant.now()
            );

            fightRepository.save(new FightEntity(fightRecord));
            for (var sender : observers.values())
                sender.fightDashboardInfo(fightRecord);
        }

        @Override
        public void disconnect() {
            sender.disconnect();
        }

        @Override
        public void equipItem(ItemId itemId) {
            Player playerData = toPlayer();
            List<ItemId> equipped = new ArrayList<>(playerData.equipped());
            List<ItemId> inventory = new ArrayList<>(playerData.inventory());

            if (!inventory.remove(itemId)) {
                sender.error("you do not have this item in inventory");
                return;
            }

            for (ItemId otherItemId : playerData.equipped()) {
                if (registry.getItemById(otherItemId).type() == registry.getItemById(itemId).type()) {
                    equipped.remove(otherItemId);
                    inventory.add(otherItemId);
                }
            }

            equipped.add(itemId);
            applySetEquipment(equipped, inventory);
            sendUpdates();
        }

        @Override
        public void unequipItem(ItemId itemId) {
            Player playerData = toPlayer();

            List<ItemId> equipped = new ArrayList<>(playerData.equipped());
            List<ItemId> inventory = new ArrayList<>(playerData.inventory());

            if (!equipped.remove(itemId)) {
                sender.error("you do not have this item equipped");
                return;
            }

            inventory.add(itemId);
            applySetEquipment(equipped, inventory);
            sendUpdates();
        }

        @Override
        public void ping() {
            sender.pong();
        }

        @Override
        public void pong() {

        }

        @Override
        public void updateLookingPosition(Position newPosition) {
            position = newPosition;
            sendUpdates();
        }

        @Override
        public void updateRealPosition(Position newPosition) {
            looking = newPosition;
        }

        public Player toPlayer() {
            return getPlayerFromEntity(playerEntity);
        }

        public PlayerWithPosition toPlayerWithPosition() {
            return new PlayerWithPosition(toPlayer(), position);
        }
    }

    public synchronized MessageToServerHandler receiveFrom(@NonNull String playerName) {
        PlayerSession handle = Objects.requireNonNull(sessions.get(playerName));

        return new MessageToServerFactory(messageToServer -> {
            synchronized (GameService.this) {
                messageToServer.process(handle);
            }
        });
    }

    private synchronized void doLogin(@NonNull String name, @NonNull Position initialPosition,
                                      @NonNull MessageToClientHandler sender) {
        log.info("doLogin({})", name);

        PlayerSession session = new PlayerSession(sender, name, initialPosition, initialPosition);
        sessions.put(name, session);
        sender.setConfig(registry.getConfig());
        session.sendUpdates();
    }

    public synchronized boolean login(String name, String password,
                                      Position initialPosition, @NonNull MessageToClientHandler networkSender) {
        MessageToClientHandler sender = new MessageToClientCacheSplitLayer(m -> m.process(networkSender));

        if (name == null || name.isEmpty() || password == null || initialPosition == null) {
            sender.error("null data passed");
            return false;
        }
        PlayerEntity entity = repository.findByName(name).orElseGet(
            () -> repository.save(new PlayerEntity(name, password))
        );
        if (!entity.hasPassword(password)) {
            sender.error("incorrect password passed");
            return false;
        }
        if (sessions.containsKey(name)) {
            sender.error("this player is already logged in");
            return false;
        }

        doLogin(name, initialPosition, sender);
        return true;
    }

    public synchronized void logout(String playerName) {
        if (!sessions.containsKey(playerName))
            return;

        log.info("logout({})", playerName);
        sessions.remove(playerName).sender.disconnect();
        for (var session : sessions.values())
            session.sender.playerDisappears(playerName);
        for (var observer : observers.values())
            observer.playerDisappears(playerName);
    }

    public synchronized void addObserver(String id, MessageToClientHandler networkObserver) {
        MessageToClientHandler observer = new MessageToClientCacheSplitLayer(m -> m.process(networkObserver));

        if (observers.put(id, observer) != null)
            throw new RuntimeException();

        sessions.values().forEach(PlayerSession::sendUpdates);
        observer.enemiesAppear(getEnemies());
    }

    public synchronized void removeObserver(String id) {
        if (observers.remove(id) == null)
            throw new RuntimeException();
    }
}
