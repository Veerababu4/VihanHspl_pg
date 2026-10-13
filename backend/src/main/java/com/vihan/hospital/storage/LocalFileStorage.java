package com.vihan.hospital.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.vihan.hospital.config.AppProperties;

/** Saves files under {@code app.upload-dir} (with {@code app.file-storage=disk}); served by {@code FileController}. */
@Service
@ConditionalOnProperty(name = "app.file-storage", havingValue = "disk")
public class LocalFileStorage implements FileStorageService {

	private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

	private final Path root;

	public LocalFileStorage(AppProperties props) {
		this.root = Path.of(props.uploadDir()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create upload directory " + root, e);
		}
	}

	@Override
	public void save(String key, byte[] content) {
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			// Write to a temp file first so a crash never leaves a half-written image behind.
			Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			Files.write(temp, content);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not save file " + key, e);
		}
	}

	@Override
	public Optional<byte[]> load(String key) {
		Path path = resolve(key);
		try {
			return Files.isRegularFile(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read file " + key, e);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		}
		catch (IOException e) {
			log.warn("Could not delete file {}: {}", key, e.getMessage());
		}
	}

	private Path resolve(String key) {
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("Invalid file key: " + key);   // path traversal guard
		}
		return path;
	}

}
