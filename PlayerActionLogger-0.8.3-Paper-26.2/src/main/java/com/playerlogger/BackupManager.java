package com.playerlogger;

import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates optional live server ZIP backups and removes expired archives. */
public final class BackupManager {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String BACKUP_PREFIX = "server-backup-";

    private final PlayerActionLogger plugin;
    private final Path serverRoot;
    private final Path backupFolder;
    private final boolean backupFolderInsideServer;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask scheduledTask;

    public BackupManager(PlayerActionLogger plugin) {
        this.plugin = plugin;
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.backupFolder = resolveBackupFolder();
        this.backupFolderInsideServer = backupFolder.startsWith(serverRoot);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PlayerActionLogger-Backup");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (!enabled()) return;
        try {
            Files.createDirectories(backupFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Automatic backups could not start: " + e.getMessage());
            return;
        }

        double intervalHours = Math.max(1.0 / 60.0,
                plugin.getConfig().getDouble("backups.interval-hours", 12.0));
        long intervalTicks = Math.max(1_200L, Math.round(intervalHours * 60.0 * 60.0 * 20.0));
        scheduledTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::requestBackup, intervalTicks, intervalTicks);
        executor.execute(this::cleanupExpiredBackups);
        plugin.getLogger().info("Automatic backups enabled every " + formatHours(intervalHours)
                + "; destination: " + backupFolder + ".");
    }

    /** Must be called from the server thread. */
    public void requestBackup() {
        if (!enabled() || !running.compareAndSet(false, true)) return;

        try {
            for (World world : plugin.getServer().getWorlds()) {
                world.save();
            }
            executor.execute(this::createBackup);
        } catch (RuntimeException e) {
            running.set(false);
            plugin.getLogger().warning("Could not begin server backup: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (scheduledTask != null) scheduledTask.cancel();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void createBackup() {
        Instant started = Instant.now();
        String filename = BACKUP_PREFIX + FILE_TIME.format(started) + ".zip";
        Path completed = backupFolder.resolve(filename);
        Path partial = backupFolder.resolve(filename + ".part");

        try {
            Files.createDirectories(backupFolder);
            Files.deleteIfExists(partial);
            try (OutputStream fileOut = Files.newOutputStream(partial,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut), StandardCharsets.UTF_8)) {
                writeMetadata(zip, started);
                Files.walkFileTree(serverRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        Path normalized = directory.toAbsolutePath().normalize();
                        if (backupFolderInsideServer && !normalized.equals(serverRoot)
                                && normalized.startsWith(backupFolder)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                        Path normalized = file.toAbsolutePath().normalize();
                        if (backupFolderInsideServer && normalized.startsWith(backupFolder)) {
                            return FileVisitResult.CONTINUE;
                        }
                        addFile(zip, file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        plugin.getLogger().warning("Backup skipped unreadable file " + file + ": " + error.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            try {
                Files.move(partial, completed, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(partial, completed, StandardCopyOption.REPLACE_EXISTING);
            }

            cleanupExpiredBackups();
            long size = Files.size(completed);
            plugin.logSystem("BACKUP_CREATED file:" + completed.getFileName()
                    + " size-bytes:" + size + " created:" + DISPLAY_TIME.format(started));
            plugin.getLogger().info("Server backup completed: " + completed + " (" + humanSize(size) + ").");
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(partial); } catch (IOException ignored) { }
            plugin.getLogger().warning("Server backup failed: " + e.getMessage());
            plugin.logSystem("BACKUP_FAILED reason:" + LogUtils.cleanOneLine(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private void addFile(ZipOutputStream zip, Path file) throws IOException {
        String entryName = serverRoot.relativize(file.toAbsolutePath().normalize()).toString()
                .replace(file.getFileSystem().getSeparator(), "/");
        if (entryName.isBlank()) return;
        ZipEntry entry = new ZipEntry(entryName);
        try {
            entry.setTime(Files.getLastModifiedTime(file).toMillis());
        } catch (IOException ignored) { }
        zip.putNextEntry(entry);
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Backup interrupted");
                if (read > 0) zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private void writeMetadata(ZipOutputStream zip, Instant created) throws IOException {
        String metadata = "PlayerActionLogger server backup\n"
                + "Created: " + DISPLAY_TIME.format(created) + "\n"
                + "Plugin version: " + plugin.getPluginMeta().getVersion() + "\n"
                + "Server: " + plugin.getServer().getVersion() + "\n"
                + "Source folder: " + serverRoot + "\n";
        zip.putNextEntry(new ZipEntry("_PlayerActionLogger_backup_info.txt"));
        zip.write(metadata.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void cleanupExpiredBackups() {
        int retentionDays = plugin.getConfig().getInt("backups.retention-days", 3);
        if (retentionDays <= 0 || !Files.isDirectory(backupFolder)) return;
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;
        try (var stream = Files.list(backupFolder)) {
            List<Path> expired = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .filter(path -> {
                        try { return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff); }
                        catch (IOException ignored) { return false; }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : expired) {
                if (Files.deleteIfExists(path)) deleted++;
            }
            if (deleted > 0) {
                plugin.getLogger().info("Deleted " + deleted + " server backup(s) older than "
                        + retentionDays + " days.");
                plugin.logSystem("BACKUP_RETENTION deleted:" + deleted + " retention-days:" + retentionDays);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Backup retention cleanup failed: " + e.getMessage());
        }
    }

    private Path resolveBackupFolder() {
        String configured = plugin.getConfig().getString("backups.folder", "backups/PlayerActionLogger");
        String safe = configured == null || configured.isBlank() ? "backups/PlayerActionLogger" : configured.trim();
        Path requested = Path.of(safe);
        Path resolved = (requested.isAbsolute() ? requested : serverRoot.resolve(requested))
                .toAbsolutePath().normalize();
        if (resolved.equals(serverRoot)) return serverRoot.resolve("backups/PlayerActionLogger").normalize();
        return resolved;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("backups.enabled", false);
    }

    private static String formatHours(double hours) {
        if (hours < 1.0) return Math.round(hours * 60.0) + " minute(s)";
        if (Math.rint(hours) == hours) return (long) hours + " hour(s)";
        return String.format(java.util.Locale.ROOT, "%.2f hour(s)", hours);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1_024L) return bytes + " B";
        if (bytes < 1_048_576L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0);
    }
}
