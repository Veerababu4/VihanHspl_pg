package com.vihan.hospital.storage;

import java.util.Optional;

/**
 * Stores uploaded files by key (e.g. {@code doctors/3f2a….jpg}); {@code FileController} serves them
 * at {@code /files/<key>}. {@code app.file-storage} picks the implementation: {@code database}
 * (default; free hosts wipe the disk on restart) or {@code disk} (DESIGN.md §7, §16).
 */
public interface FileStorageService {

	void save(String key, byte[] content);

	/** The file's bytes, or empty if there is no such file. */
	Optional<byte[]> load(String key);

	/** Deletes the file if it exists; missing files are ignored. */
	void delete(String key);

}
