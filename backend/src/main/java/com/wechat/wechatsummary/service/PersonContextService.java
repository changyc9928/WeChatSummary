package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.dto.PersonContextDto;
import com.wechat.wechatsummary.dto.PersonDto;
import com.wechat.wechatsummary.dto.PersonRelationshipDto;
import com.wechat.wechatsummary.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Step 3 lightweight person-context store.
 *
 * <p>File-based sidecar ({@code {uuid}_person_context.json} under outputs),
 * scoped to one summary time window. No knowledge graph, no vector DB, no
 * long-term profiles — a different date range regenerates it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonContextService {

    private final AiService aiService;
    private final StoragePaths storagePaths;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int EXTRACT_SAMPLE_CHARS = 18000;

    /**
     * LLM Call #1: extract the preliminary context for the current window and
     * persist it. Never throws for LLM failures — returns an empty context so
     * the original summary flow can continue.
     */
    public PersonContextDto extractAndSave(String userId, UUID uuid, LocalDateTime startTime,
        LocalDateTime endTime) {
        PersonContextDto dto;
        try {
            Path targetFile = locateProcessedFile(storagePaths.outputDir(userId), uuid);
            String rawContent = Files.readString(targetFile, StandardCharsets.UTF_8);
            if (startTime != null || endTime != null) {
                rawContent = filterContentTimeWindow(rawContent, startTime, endTime);
            }
            String sample = rawContent.length() > EXTRACT_SAMPLE_CHARS
                ? rawContent.substring(0, EXTRACT_SAMPLE_CHARS)
                : rawContent;
            dto = aiService.extractPersonContext(sample);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Person-context extraction failed, using empty context: {}", e.getMessage());
            dto = new PersonContextDto();
        }
        if (dto == null) {
            dto = new PersonContextDto();
        }
        dto = normalize(dto);
        dto.setStartTime(startTime);
        dto.setEndTime(endTime);
        save(userId, uuid, dto);
        return dto;
    }

    public PersonContextDto load(String userId, UUID uuid) {
        Path file = storagePaths.personContextFile(userId, uuid.toString());
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            PersonContextDto dto = MAPPER.readValue(json, PersonContextDto.class);
            return normalize(dto);
        } catch (Exception e) {
            log.warn("Failed to read person-context sidecar, ignoring: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Loads the stored context only when its window matches the requested
     * summary window; otherwise returns null so stale context from another
     * date range is never reused.
     */
    public PersonContextDto loadForWindow(String userId, UUID uuid, LocalDateTime startTime,
        LocalDateTime endTime) {
        PersonContextDto dto = load(userId, uuid);
        if (dto == null || isEmpty(dto)) {
            return null;
        }
        if (!sameWindow(dto.getStartTime(), startTime) || !sameWindow(dto.getEndTime(),
            endTime)) {
            log.info("Stored person-context window does not match requested window, skipping.");
            return null;
        }
        return dto;
    }

    public PersonContextDto save(String userId, UUID uuid, PersonContextDto dto) {
        PersonContextDto normalized = normalize(dto == null ? new PersonContextDto() : dto);
        try {
            Path file = storagePaths.personContextFile(userId, uuid.toString());
            Files.createDirectories(file.getParent());
            String json = MAPPER.writeValueAsString(normalized);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.warn("Failed to persist person-context sidecar: {}", e.getMessage());
        }
        return normalized;
    }

    public boolean isEmpty(PersonContextDto dto) {
        return dto == null || dto.getPeople() == null || dto.getPeople().isEmpty();
    }

    /** Normalizes ids / names / aliases / relationships; drops invalid edges. */
    public PersonContextDto normalize(PersonContextDto dto) {
        if (dto == null) {
            return new PersonContextDto();
        }
        List<PersonDto> people = new ArrayList<>();
        Map<String, String> idByName = new LinkedHashMap<>();
        int idx = 0;
        if (dto.getPeople() != null) {
            for (PersonDto p : dto.getPeople()) {
                if (p == null) {
                    continue;
                }
                String name = p.getName() == null ? "" : p.getName().trim();
                if (name.isEmpty()) {
                    continue;
                }
                idx++;
                String id = p.getId() == null || p.getId().isBlank() ? "p" + idx
                    : p.getId().trim();
                // De-duplicate by name: same display name = same person.
                if (idByName.containsKey(name)) {
                    continue;
                }
                idByName.put(name, id);
                List<String> aliases = new ArrayList<>();
                if (p.getAliases() != null) {
                    for (String a : p.getAliases()) {
                        if (a == null) {
                            continue;
                        }
                        String av = a.trim();
                        if (!av.isEmpty() && !av.equals(name) && !aliases.contains(av)) {
                            aliases.add(av);
                        }
                    }
                }
                people.add(new PersonDto(id, name, aliases));
            }
        }
        Map<String, String> validIds = new LinkedHashMap<>();
        for (PersonDto p : people) {
            validIds.put(p.getId(), p.getName());
        }
        List<PersonRelationshipDto> rels = new ArrayList<>();
        if (dto.getRelationships() != null) {
            for (PersonRelationshipDto r : dto.getRelationships()) {
                if (r == null || r.getFrom() == null || r.getTo() == null
                    || r.getRelationship() == null) {
                    continue;
                }
                String from = r.getFrom().trim();
                String to = r.getTo().trim();
                String rel = r.getRelationship().trim();
                if (from.isEmpty() || to.isEmpty() || rel.isEmpty()) {
                    continue;
                }
                if (!validIds.containsKey(from) || !validIds.containsKey(to)
                    || from.equals(to)) {
                    continue;
                }
                rels.add(new PersonRelationshipDto(from, to, rel));
            }
        }
        PersonContextDto out = new PersonContextDto();
        out.setPeople(people);
        out.setRelationships(rels);
        out.setStartTime(dto.getStartTime());
        out.setEndTime(dto.getEndTime());
        return out;
    }

    /**
     * Renders the user-confirmed block injected before the original chat
     * content. Explicitly marked as user-confirmed with higher priority than
     * model speculation.
     */
    public String formatForPrompt(PersonContextDto dto) {
        if (isEmpty(dto)) {
            return "";
        }
        Map<String, String> nameById = new LinkedHashMap<>();
        for (PersonDto p : dto.getPeople()) {
            nameById.put(p.getId(), p.getName());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前聊天人物 Context\n\n");
        sb.append("以下信息用于帮助你正确理解当前聊天中的人物。\n\n");
        sb.append("这些信息来自用户确认后的 Context，应优先于模型自行推测。\n");
        sb.append("以下人物信息已经由用户确认或修改。这些信息优先级高于模型自行推测。不要自行修改这些人物身份和别名。\n\n");
        sb.append("人物：\n\n");
        for (PersonDto p : dto.getPeople()) {
            sb.append(p.getName()).append("\n");
            if (p.getAliases() == null || p.getAliases().isEmpty()) {
                sb.append("- 无其他别名\n");
            } else {
                sb.append("- 别名：").append(String.join("、", p.getAliases())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("人物关系：\n\n");
        if (dto.getRelationships() == null || dto.getRelationships().isEmpty()) {
            sb.append("- 暂无可靠关系信息，不要编造人物关系。\n\n");
        } else {
            for (PersonRelationshipDto r : dto.getRelationships()) {
                String fromName = nameById.getOrDefault(r.getFrom(), r.getFrom());
                String toName = nameById.getOrDefault(r.getTo(), r.getTo());
                sb.append("- ").append(fromName).append(" 与 ").append(toName).append("：")
                    .append(r.getRelationship()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("注意：\n");
        sb.append("- 不要把同一个人的不同别名当成不同的人。\n");
        sb.append("- 不要把没有出现在当前聊天中的群成员加入总结。\n");
        sb.append("- 不要因为一次争论就推断两个人长期关系恶劣。\n");
        sb.append("- 如果聊天内容与 Context 明显冲突，应以当前聊天实际内容为依据，并谨慎描述。\n");
        return sb.toString();
    }

    private boolean sameWindow(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.isEqual(b);
    }

    private Path locateProcessedFile(Path userOutputsDir, UUID uuid) throws Exception {
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";
        if (!Files.exists(userOutputsDir)) {
            throw new ResourceNotFoundException(
                "Processed markdown source directory not found for trace: " + filePrefix);
        }
        try (var stream = Files.list(userOutputsDir)) {
            return stream
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(filePrefix) && name.endsWith(fileSuffix);
                })
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Processed markdown source file not found for trace: " + filePrefix));
        }
    }

    /** Same window filter as the summary pipeline (kept local to avoid touching Step 1/2). */
    private String filterContentTimeWindow(String rawContent, LocalDateTime startTime,
        LocalDateTime endTime) {
        List<String> lines = List.of(rawContent.split("\n", -1));
        List<String> filteredLines = new ArrayList<>();

        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
            .optionalEnd()
            .toFormatter();
        Pattern timestampPattern = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)]");

        boolean keepLine = (startTime == null);
        for (String line : lines) {
            if (line.startsWith("---") || line.startsWith("群名称") || line.startsWith(
                "总消息数")) {
                filteredLines.add(line);
                continue;
            }
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.find()) {
                LocalDateTime lineTime;
                try {
                    lineTime = LocalDateTime.parse(matcher.group(1), formatter);
                } catch (Exception e) {
                    if (keepLine) {
                        filteredLines.add(line);
                    }
                    continue;
                }
                if (!keepLine && startTime != null && !lineTime.isBefore(startTime)) {
                    keepLine = true;
                }
                if (endTime != null && lineTime.isAfter(endTime)) {
                    break;
                }
            }
            if (keepLine) {
                filteredLines.add(line);
            }
        }
        if (filteredLines.isEmpty()) {
            return rawContent;
        }
        return String.join("\n", filteredLines);
    }
}
