package com.agentcore.spring.web;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.ModelGatewayFactory;
import com.agentcore.interfaces.web.ModelCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 页面上配置的模型列表，保存在服务端的一个 JSON 文件里（默认 ~/.agentcore/web-models/models.json）。
 *
 * <ul>
 *   <li>内置两个只读的选项：server-default（启动时配置的模型）和 mock（离线假模型）；</li>
 *   <li>其余是管理员在页面上新建的配置（{@link ModelProfile}），最多 {@value #MAX_PROFILES} 个；</li>
 *   <li>文件里的 API key 是<b>明文</b>，由 {@link PrivateSettingsFile} 保证只有当前用户能读写；
 *       任何接口都只返回"有没有 key"，不返回 key 本身；</li>
 *   <li>修改先写文件，写成功了才生效（{@link #publish}）；读的一方拿到的总是一个完整的快照。</li>
 * </ul>
 * 文件为 null 时只保存在内存里，重启后丢失。
 */
public final class FileModelCatalog implements ModelCatalog {

    private static final System.Logger LOG = System.getLogger(FileModelCatalog.class.getName());

    static final String SERVER_DEFAULT = "server-default";
    private static final String MOCK = ModelProfileParser.MOCK;
    private static final int MAX_PROFILES = 64;
    /** 配置 ID：小写字母开头，只含小写字母、数字、- 和 _，最长 64。 */
    private static final String ID_PATTERN = "[a-z][a-z0-9_-]{0,63}";
    private static final Set<String> RESERVED_IDS = Set.of(SERVER_DEFAULT, MOCK, "default");
    /** 给页面配置的模型起的内部 provider 名，避免套用内置预设或启动配置里的 key、请求头。 */
    private static final String PROFILE_PROVIDER = "web-profile";

    /** 某一时刻的完整状态：默认模型 + 全部配置。不可变，整个替换。 */
    private record Snapshot(String defaultId, Map<String, ModelProfile> profiles) {
        Snapshot {
            profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        }
    }

    private final ModelGateway serverGateway;
    private final PrivateSettingsFile file;
    private volatile Snapshot state = new Snapshot(SERVER_DEFAULT, Map.of());

    /**
     * @param serverGateway 启动时配置的模型（server-default），只借用、不关闭
     * @param file          保存配置的文件；null 表示只放在内存里
     * @throws IllegalStateException 文件存在但读不了或内容不对；信息里有文件路径和具体原因
     */
    public FileModelCatalog(ModelGateway serverGateway, Path file) {
        this.serverGateway = Objects.requireNonNull(serverGateway);
        this.file = file == null ? null : new PrivateSettingsFile(file);
        load();
    }

    // ------------------------------------------------------------------ 查询

    /** 发消息时可以选的模型（不含地址和 key）。canManage：调用者能不能打开"模型设置"。 */
    @Override
    public ObjectNode models(boolean canManage) {
        Snapshot current = state;
        ObjectNode result = Json.object()
                .put("default_id", current.defaultId())
                .put("can_manage", canManage)
                .put("persisted", file != null);
        ArrayNode models = result.putArray("models");
        models.addObject().put("id", SERVER_DEFAULT).put("label", "服务端默认模型")
                .put("model", serverGateway.model()).put("read_only", true);
        models.addObject().put("id", MOCK).put("label", "离线模拟模型").put("model", MOCK).put("read_only", true);
        for (ModelProfile profile : current.profiles().values()) {
            models.addObject().put("id", profile.id()).put("label", profile.label())
                    .put("model", profile.model()).put("read_only", false);
        }
        return result;
    }

    /** 管理员看到的完整配置（地址、上限等），API key 只显示有没有。 */
    @Override
    public ObjectNode settings() {
        Snapshot current = state;
        ObjectNode result = Json.object().put("default_id", current.defaultId()).put("persisted", file != null);
        ArrayNode profiles = result.putArray("profiles");
        current.profiles().values().forEach(profile -> profiles.add(profile.publicView()));
        return result;
    }

    // ------------------------------------------------------------------ 修改

    @Override
    public synchronized void put(String id, JsonNode body) {
        requireCustomId(id);
        Snapshot current = state;
        boolean isNew = !current.profiles().containsKey(id);
        if (isNew && current.profiles().size() >= MAX_PROFILES) {
            throw new Failure(400, "model profile limit reached");
        }
        ModelProfile profile = ModelProfileParser.parse(id, body, current.profiles().get(id));
        Map<String, ModelProfile> updated = new LinkedHashMap<>(current.profiles());
        updated.put(id, profile);
        publish(new Snapshot(current.defaultId(), updated));
    }

    /** 删掉的正好是默认模型时，默认模型回到 server-default。 */
    @Override
    public synchronized void delete(String id) {
        requireCustomId(id);
        Snapshot current = state;
        if (!current.profiles().containsKey(id)) {
            throw new Failure(404, "model profile not found");
        }
        Map<String, ModelProfile> updated = new LinkedHashMap<>(current.profiles());
        updated.remove(id);
        String defaultId = id.equals(current.defaultId()) ? SERVER_DEFAULT : current.defaultId();
        publish(new Snapshot(defaultId, updated));
    }

    @Override
    public synchronized void setDefault(String id) {
        Snapshot current = state;
        requireRegistered(current, id);
        publish(new Snapshot(id, current.profiles()));
    }

    /** 先写文件，写成功了才替换内存里的状态：失败时一切保持原样。 */
    private void publish(Snapshot next) {
        if (file != null) {
            persist(next);
        }
        state = next;
    }

    // ------------------------------------------------------------------ 选择模型（每次运行一个）

    /**
     * 为一次运行准备模型。server-default 借用启动时的网关（用完不关闭）；
     * 其余按配置新建一个网关，归这次运行所有，运行结束时关闭（见 {@link Selection}）。
     *
     * @param id 为 null 时用默认模型
     */
    @Override
    public Selection select(String id) {
        Snapshot current = state;
        String selected = id == null ? current.defaultId() : id;
        requireRegistered(current, selected);
        if (SERVER_DEFAULT.equals(selected)) {
            return new Selection(selected, serverGateway, false);
        }
        try {
            ModelProfile profile = current.profiles().get(selected);
            boolean mock = MOCK.equals(selected) || MOCK.equals(profile.type());
            ModelGateway gateway = mock ? createMockGateway() : createGateway(profile);
            return new Selection(selected, new CredentialSafeGateway(gateway), true);
        } catch (RuntimeException creationFailed) {
            // 不带出原始异常：里面可能有地址或 key
            throw new Failure(400, "model gateway could not be created");
        }
    }

    private static ModelGateway createMockGateway() {
        return new ModelGatewayFactory(Map.of(), ignoredVariable -> null).create(MOCK);
    }

    /** 只用这份配置里的值：不读环境变量里的 key，也不继承启动配置的请求头和其他选项。 */
    private static ModelGateway createGateway(ModelProfile profile) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", profile.type());
        options.put("base_url", profile.baseUrl());
        options.put("api_key", profile.apiKey());
        options.put("max_tokens", profile.maxTokens());
        options.put("context_window", profile.contextWindow());
        options.put("inherit_environment", false);
        ModelGatewayFactory factory = new ModelGatewayFactory(Map.of(PROFILE_PROVIDER, options), ignoredVariable -> null);
        return factory.create(PROFILE_PROVIDER + ":" + profile.model());
    }

    // ------------------------------------------------------------------ ID 规则

    private static void requireValidId(String id) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new Failure(400, "invalid model id");
        }
    }

    /** 页面能新建、修改、删除的 ID：合法，且不是内置的保留名字。 */
    private static void requireCustomId(String id) {
        requireValidId(id);
        if (RESERVED_IDS.contains(id)) {
            throw new Failure(400, "reserved model id");
        }
    }

    /** 能选用的 ID：内置的两个，或者已经配置过的。 */
    private static void requireRegistered(Snapshot snapshot, String id) {
        requireValidId(id);
        boolean builtIn = SERVER_DEFAULT.equals(id) || MOCK.equals(id);
        if (!builtIn && !snapshot.profiles().containsKey(id)) {
            throw new Failure(404, "model profile not found");
        }
    }

    // ------------------------------------------------------------------ 文件

    /**
     * 启动时读取。读不了或内容不对时拒绝启动（文件里有 key，宁可停下来让人处理，也不悄悄忽略或覆盖）；
     * 错误信息给出文件路径和具体原因，但不包含文件内容。
     */
    private void load() {
        if (file == null) {
            return;
        }
        try {
            Optional<String> content = file.read();
            if (content.isPresent()) {
                state = parseStoredSettings(content.get());
            }
        } catch (PrivateSettingsFile.Problem problem) {
            throw new IllegalStateException("could not load model settings from " + file.path() + ": " + problem.getMessage()
                    + ". Fix or remove the file (it holds API keys), or set agentcore.web.models.file");
        }
    }

    private static Snapshot parseStoredSettings(String content) throws PrivateSettingsFile.Problem {
        JsonNode stored;
        try {
            stored = Json.read(content);
        } catch (RuntimeException notJson) {
            throw new PrivateSettingsFile.Problem("file is not valid JSON");
        }
        boolean expectedShape = stored != null && stored.isObject() && stored.path("profiles").isArray()
                && stored.path("default_id").isTextual();
        if (!expectedShape) {
            throw new PrivateSettingsFile.Problem("file must be an object with default_id and profiles");
        }
        if (stored.path("profiles").size() > MAX_PROFILES) {
            throw new PrivateSettingsFile.Problem("file has more than " + MAX_PROFILES + " profiles");
        }
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        int position = 0;
        for (JsonNode entry : stored.path("profiles")) {
            position++;
            ModelProfile profile = parseStoredProfile(entry, position);
            if (profiles.putIfAbsent(profile.id(), profile) != null) {
                throw new PrivateSettingsFile.Problem("profile id '" + profile.id() + "' appears more than once");
            }
        }
        Snapshot snapshot = new Snapshot(stored.path("default_id").textValue(), profiles);
        try {
            requireRegistered(snapshot, snapshot.defaultId());
        } catch (Failure unknownDefault) {
            throw new PrivateSettingsFile.Problem("default_id does not name a defined profile");
        }
        return snapshot;
    }

    /** 第 position 个配置。只在 ID 本身合法时才把它写进错误信息。 */
    private static ModelProfile parseStoredProfile(JsonNode entry, int position) throws PrivateSettingsFile.Problem {
        String id;
        try {
            id = ModelProfileParser.text(entry, "id", 64, null);
            requireCustomId(id);
        } catch (Failure invalidId) {
            throw new PrivateSettingsFile.Problem("profile #" + position + " has an invalid id");
        }
        try {
            return ModelProfileParser.parse(id, entry, null);
        } catch (Failure invalidProfile) {
            throw new PrivateSettingsFile.Problem("profile '" + id + "' is invalid: " + invalidProfile.getMessage());
        }
    }

    /** 写入失败时，接口只返回固定的错误（不暴露服务端路径），具体原因写进服务端日志。 */
    private void persist(Snapshot next) {
        ObjectNode stored = Json.object().put("default_id", next.defaultId());
        ArrayNode profiles = stored.putArray("profiles");
        next.profiles().values().forEach(profile -> profiles.add(profile.storedView()));
        try {
            file.write(Json.write(stored));
        } catch (PrivateSettingsFile.Problem problem) {
            LOG.log(System.Logger.Level.WARNING, "could not persist model settings to " + file.path() + ": " + problem.getMessage());
            throw new Failure(500, "could not persist model settings");
        }
    }
}
