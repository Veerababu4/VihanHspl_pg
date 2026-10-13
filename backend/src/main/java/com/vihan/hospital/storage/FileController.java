package com.vihan.hospital.storage;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves uploaded images at {@code /files/<key>} (public, like the pages that show them). */
@RestController
public class FileController {

	/** Keys made by {@link ImageService}: {@code folder/name.jpg}; anything else is "not found". */
	private static final Pattern KEY = Pattern.compile("[a-z0-9_-]+/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)");

	private final FileStorageService storage;

	public FileController(FileStorageService storage) {
		this.storage = storage;
	}

	/** File names are random and never reused, so they can be cached for long. */
	@GetMapping("/files/{*key}")
	public ResponseEntity<byte[]> file(@PathVariable String key) {
		String k = key.startsWith("/") ? key.substring(1) : key;
		if (!KEY.matcher(k).matches()) {
			return ResponseEntity.notFound().build();
		}
		return storage.load(k)
			.map(bytes -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(contentType(k)))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
				.body(bytes))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	static String contentType(String key) {
		String k = key.toLowerCase(Locale.ROOT);
		if (k.endsWith(".png")) {
			return MediaType.IMAGE_PNG_VALUE;
		}
		if (k.endsWith(".webp")) {
			return "image/webp";
		}
		return MediaType.IMAGE_JPEG_VALUE;
	}

}
