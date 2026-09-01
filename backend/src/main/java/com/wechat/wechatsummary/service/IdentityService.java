package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds and resolves the wxid-keyed identity registry for a chat.
 *
 * <p>Identities are anchored on the WeChat talker id ({@code senderUsername}) extracted from the raw
 * export — that is the only stable identifier and is used as the primary key. On top of that we
 * layer:
 * <ul>
 *   <li>operator-supplied aliases / social handles from a per-chat sidecar file, and</li>
 *   <li>a best-effort LLM pass that detects referenced nicknames and maps them back to a known
 *       wxid.</li>
 * </ul>
 * The sidecar always wins over the inferred mapping.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityService {

    private final AiService aiService;
    private final StoragePaths storagePaths;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Builds the full registry for a chat.
     *
     * <p>Resolution is fully automatic and data-driven — no chat-specific mappings are hardcoded:
     * <ol>
     *   <li>sender identities from the raw export (wxid is the reliable primary key), or, when no
     *       export is available, from the processed markdown's {@code [ts] Name:} lines;</li>
     *   <li>an <em>optional</em> per-chat sidecar ({@code {uuid}_identities.json}) that an operator
     *       may add to correct/extend a specific chat — it is never required and defaults to empty,
     *       so every chat works out of the box;</li>
     *   <li>a best-effort LLM pass that maps referenced nicknames back to a known identity.</li>
     * </ol>
     *
     * @param userId      owner of the workspace
     * @param uuid        task id
     * @param sampleText  a representative slice of the conversation used for alias inference
     * @param markdown    the full processed markdown (used as a sender fallback when no export)
     * @return participants keyed by their id (wxid when available, otherwise canonical name)
     */
    public Map<String, Participant> buildRegistry(String userId, String uuid, String sampleText,
        String markdown) {
        Map<String, Participant> registry = new LinkedHashMap<>();

        PathHolder paths = new PathHolder(
            storagePaths.rawExportJson(userId, uuid),
            storagePaths.identitiesFile(userId, uuid));

        // 1. Sender-derived identities (reliable wxid -> display names).
        if (paths.rawJson != null && Files.exists(paths.rawJson)) {
            loadFromExport(paths.rawJson, registry);
        }

        // 1b. Fallback: derive participants from the markdown's sender lines when there is no
        //     raw export (keeps the feature working for every chat, wxid simply unknown).
        if (registry.isEmpty() && markdown != null && !markdown.isBlank()) {
            loadSendersFromMarkdown(markdown, registry);
        }

        // 2. Optional operator sidecar (authoritative aliases + social handles). Empty by default.
        if (paths.sidecar != null && Files.exists(paths.sidecar)) {
            loadFromSidecar(paths.sidecar, registry);
        }

        // 3. Best-effort LLM alias discovery (sidecar already applied, so it can only add).
        if (sampleText != null && !sampleText.isBlank() && !registry.isEmpty()) {
            try {
                String roster = formatRoster(registry);
                String mentionHint = computeMentionHints(paths.rawJson, registry);
                Map<String, String> discovered = aiService.discoverAliases(sampleText, roster,
                    mentionHint);
                applyDiscovered(discovered, registry);
            } catch (Exception e) {
                log.warn("Alias discovery failed, continuing with export+sidecar only: {}",
                    e.getMessage());
            }
        }

        return registry;
    }

    private void loadSendersFromMarkdown(String markdown, Map<String, Participant> registry) {
        // Lines look like: [2026-08-18 19:50:12] 小鱼干ovo: @yuigon
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "^\\[[^\\]]*\\]\\s+([^:\\n]+):");
        for (String line : markdown.split("\n")) {
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty() && !registry.containsKey(name)) {
                    registry.put(name, new Participant(null, name));
                }
            }
        }
    }

    private void loadFromExport(java.nio.file.Path rawJson, Map<String, Participant> registry) {
        try {
            JsonNode root = objectMapper.readTree(rawJson.toFile());
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray()) {
                return;
            }
            Map<String, java.util.Map<String, Integer>> freq = new LinkedHashMap<>();
            for (JsonNode m : messages) {
                JsonNode u = m.get("senderUsername");
                JsonNode n = m.get("senderDisplayName");
                if (u == null || u.isNull() || n == null || n.isNull()) {
                    continue;
                }
                String wxid = u.asText();
                String name = n.asText();
                freq.computeIfAbsent(wxid, k -> new LinkedHashMap<>())
                    .merge(name, 1, Integer::sum);
            }
            for (Map.Entry<String, java.util.Map<String, Integer>> e : freq.entrySet()) {
                String wxid = e.getKey();
                String canonical = e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
                Participant p = new Participant(wxid, canonical);
                e.getValue().keySet().forEach(p.getDisplayNames()::add);
                registry.put(p.getId(), p);
            }
        } catch (IOException e) {
            log.warn("Failed to parse raw export for identity registry: {}", e.getMessage());
        }
    }

    private void loadFromSidecar(java.nio.file.Path sidecar, Map<String, Participant> registry) {
        try {
            JsonNode root = objectMapper.readTree(sidecar.toFile());
            JsonNode participants = root.get("participants");
            if (participants == null || !participants.isObject()) {
                return;
            }
            participants.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode node = entry.getValue();
                Participant p = resolveOrCreate(key, registry);
                if (p == null) {
                    return;
                }
                JsonNode canonical = node.get("canonicalName");
                if (canonical != null && !canonical.isNull()) {
                    p.setCanonicalName(canonical.asText());
                }
                JsonNode aliases = node.get("aliases");
                if (aliases != null && aliases.isArray()) {
                    aliases.forEach(a -> p.getAliases().add(a.asText()));
                }
                JsonNode social = node.get("social");
                if (social != null && social.isObject()) {
                    social.fields().forEachRemaining(s -> p.getSocialHandles().put(s.getKey(),
                        s.getValue().asText()));
                }
            });
        } catch (IOException e) {
            log.warn("Failed to parse identity sidecar: {}", e.getMessage());
        }
    }

    /**
     * Scans the raw export for "@name" mentions that are never used as a sender (i.e. names that
     * exist only as handles directed at someone) and records who tends to reply right after such a
     * mention. This surfaces the common case where a participant is addressed under a different
     * handle (e.g. "@杰" actually reaches 苦雪) so the LLM alias pass can unify them without any
     * operator-supplied sidecar.
     */
    private String computeMentionHints(java.nio.file.Path rawJson,
        Map<String, Participant> registry) {
        if (rawJson == null || !Files.exists(rawJson)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson.toFile());
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray() || messages.size() == 0) {
                return "";
            }
            java.util.Set<String> knownNames = new java.util.HashSet<>();
            registry.values().forEach(p -> knownNames.addAll(p.allNames()));

            java.util.regex.Pattern mentionPat = java.util.regex.Pattern.compile(
                "@([^\\s@：:，。、!！?？~～]+)");
            Map<String, Map<String, Integer>> replyCounts = new LinkedHashMap<>();
            Map<String, java.util.List<String>> samples = new LinkedHashMap<>();

            for (int i = 0; i < messages.size(); i++) {
                JsonNode m = messages.get(i);
                JsonNode contentNode = m.get("content");
                if (contentNode == null || contentNode.isNull()) {
                    continue;
                }
                String content = contentNode.asText();
                java.util.regex.Matcher matcher = mentionPat.matcher(content);
                java.util.Set<String> mentioned = new java.util.LinkedHashSet<>();
                while (matcher.find()) {
                    mentioned.add(matcher.group(1).trim());
                }
                if (mentioned.isEmpty()) {
                    continue;
                }
                JsonNode next = (i + 1 < messages.size()) ? messages.get(i + 1) : null;
                String nextSpeaker = (next != null && next.get("senderDisplayName") != null
                    && !next.get("senderDisplayName").isNull())
                    ? next.get("senderDisplayName").asText() : null;
                for (String name : mentioned) {
                    if (knownNames.contains(name)) {
                        continue;
                    }
                    replyCounts.computeIfAbsent(name, k -> new LinkedHashMap<>())
                        .merge(nextSpeaker == null ? "<none>" : nextSpeaker, 1, Integer::sum);
                    samples.computeIfAbsent(name, k -> new java.util.ArrayList<>())
                        .add((m.get("senderDisplayName") == null ? "?" : m.get("senderDisplayName").asText())
                            + " @ " + name + ": " + content.replace("\n", " ").trim().substring(0,
                                Math.min(content.replace("\n", " ").trim().length(), 80))
                            + "  → 下一发言: " + (nextSpeaker == null ? "?" : nextSpeaker));
                }
            }
            if (replyCounts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Map<String, Integer>> e : replyCounts.entrySet()) {
                String name = e.getKey();
                int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                String top = e.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(en -> en.getKey() + "(" + en.getValue() + ")")
                    .collect(Collectors.joining("、"));
                sb.append("- @").append(name).append("（被@提及 ").append(total)
                    .append(" 次，之后通常由 ").append(top).append(" 接话）\n");
                samples.getOrDefault(name, java.util.Collections.emptyList()).stream()
                    .limit(6).forEach(s -> sb.append("    例：").append(s).append("\n"));
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("Failed to compute mention hints: {}", e.getMessage());
            return "";
        }
    }

    private Participant resolveOrCreate(String key, Map<String, Participant> registry) {
        // Exact id match (wxid or canonical name).
        if (registry.containsKey(key)) {
            return registry.get(key);
        }
        // Match any known name/alias.
        for (Participant p : registry.values()) {
            if (p.allNames().contains(key)) {
                return p;
            }
        }
        // Unknown key -> create a referenced-only participant keyed by this name.
        Participant p = new Participant(null, key);
        registry.put(p.getId(), p);
        return p;
    }

    private void applyDiscovered(Map<String, String> discovered,
        Map<String, Participant> registry) {
        for (Map.Entry<String, String> e : discovered.entrySet()) {
            String alias = e.getKey();
            String target = e.getValue();
            Participant p = registry.get(target);
            if (p == null) {
                // target may be a display name rather than a wxid
                for (Participant cand : registry.values()) {
                    if (cand.allNames().contains(target)) {
                        p = cand;
                        break;
                    }
                }
            }
            if (p != null && !alias.isBlank()) {
                p.getAliases().add(alias.trim());
            }
        }
    }

    /**
     * Renders the registry as a compact, model-friendly roster block for injection into prompts.
     */
    public String formatRoster(Map<String, Participant> registry) {
        if (registry.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("群成员身份表（微信ID为唯一标识，总结时请统一使用【规范名】，不要将同一人的不同称呼当成不同的人）：\n");
        for (Participant p : registry.values()) {
            sb.append("- ");
            if (p.getWxid() != null) {
                sb.append(p.getWxid()).append(" ");
            }
            sb.append("【").append(p.getCanonicalName()).append("】");
            if (!p.getDisplayNames().isEmpty()) {
                sb.append(" 已知称呼：").append(String.join("、", p.getDisplayNames()));
            }
            if (!p.getAliases().isEmpty()) {
                sb.append(" 其他外号/代称：").append(String.join("、", p.getAliases()));
            }
            if (!p.getSocialHandles().isEmpty()) {
                String social = p.getSocialHandles().entrySet().stream()
                    .map(en -> en.getKey() + ":" + en.getValue())
                    .collect(Collectors.joining("，"));
                sb.append(" 社媒：").append(social);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static final class PathHolder {

        final java.nio.file.Path rawJson;
        final java.nio.file.Path sidecar;

        PathHolder(java.nio.file.Path rawJson, java.nio.file.Path sidecar) {
            this.rawJson = rawJson;
            this.sidecar = sidecar;
        }
    }
}
