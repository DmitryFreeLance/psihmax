package ru.psihmax.bot.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.config.BotProperties;

@Component
public class AdminStore {
    private final ObjectMapper objectMapper;
    private final Path usersFile;
    private final Path adminsFile;
    private final long bootstrapAdminId;
    private final Map<Long, UserProfile> users;
    private final Set<Long> admins;

    public AdminStore(BotProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Path dataPath = Path.of(properties.dataFile());
        Path dataDir = dataPath.getParent() == null ? Path.of("data") : dataPath.getParent();
        this.usersFile = dataDir.resolve("users.json");
        this.adminsFile = dataDir.resolve("admins.json");
        this.bootstrapAdminId = properties.max().adminUserId();
        this.users = loadUsers();
        this.admins = loadAdmins();
        if (bootstrapAdminId > 0) {
            this.admins.add(bootstrapAdminId);
            flushAdmins();
        }
    }

    public synchronized void rememberUser(long userId, String displayName) {
        String name = displayName == null || displayName.isBlank() ? "Пользователь " + userId : displayName;
        users.put(userId, new UserProfile(userId, name, Instant.now().toString()));
        flushUsers();
    }

    public synchronized Optional<UserProfile> findUser(long userId) {
        return Optional.ofNullable(users.get(userId));
    }

    public synchronized List<UserProfile> knownUsers() {
        return users.values().stream()
                .sorted(Comparator.comparing(UserProfile::lastSeenAt, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
    }

    public synchronized boolean isAdmin(long userId) {
        return userId > 0 && (userId == bootstrapAdminId || admins.contains(userId));
    }

    public synchronized void addAdmin(long userId) {
        admins.add(userId);
        flushAdmins();
    }

    public synchronized List<Long> adminIds() {
        Set<Long> all = new HashSet<>(admins);
        if (bootstrapAdminId > 0) {
            all.add(bootstrapAdminId);
        }
        return all.stream().filter(id -> id > 0).sorted().toList();
    }

    private Map<Long, UserProfile> loadUsers() {
        if (!Files.exists(usersFile)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(usersFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read users file " + usersFile, e);
        }
    }

    private Set<Long> loadAdmins() {
        if (!Files.exists(adminsFile)) {
            return new HashSet<>();
        }
        try {
            return objectMapper.readValue(adminsFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read admins file " + adminsFile, e);
        }
    }

    private void flushUsers() {
        write(usersFile, users);
    }

    private void flushAdmins() {
        write(adminsFile, admins);
    }

    private void write(Path file, Object value) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write file " + file, e);
        }
    }
}
