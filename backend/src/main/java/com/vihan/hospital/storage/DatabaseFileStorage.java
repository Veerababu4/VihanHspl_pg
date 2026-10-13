package com.vihan.hospital.storage;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Keeps uploaded files in the {@code stored_file} table (V5). A save inside a transaction is
 * rolled back with it; {@code ImageService.cleanupOnCompletion} still removes replaced files.
 */
@Service
@ConditionalOnProperty(name = "app.file-storage", havingValue = "database", matchIfMissing = true)
public class DatabaseFileStorage implements FileStorageService {

	private final JdbcTemplate jdbc;

	public DatabaseFileStorage(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void save(String key, byte[] content) {
		jdbc.update("""
				INSERT INTO stored_file (file_key, content_type, content) VALUES (?, ?, ?)
				ON CONFLICT (file_key) DO UPDATE SET content_type = EXCLUDED.content_type, content = EXCLUDED.content""",
				key, FileController.contentType(key), content);
	}

	@Override
	public Optional<byte[]> load(String key) {
		List<byte[]> rows = jdbc.query("SELECT content FROM stored_file WHERE file_key = ?",
				(rs, i) -> rs.getBytes(1), key);
		return rows.stream().findFirst();
	}

	@Override
	public void delete(String key) {
		jdbc.update("DELETE FROM stored_file WHERE file_key = ?", key);
	}

}
