package com.vihan.hospital.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.vihan.hospital.common.error.ApiException;
import com.vihan.hospital.storage.ImageService.Kind;
import com.vihan.hospital.storage.ImageService.StoredImage;

class ImageServiceTest {

	/** Keeps saved files in memory. */
	static class MemoryStorage implements FileStorageService {
		final Map<String, byte[]> files = new HashMap<>();

		@Override
		public void save(String key, byte[] content) {
			files.put(key, content);
		}

		@Override
		public Optional<byte[]> load(String key) {
			return Optional.ofNullable(files.get(key));
		}

		@Override
		public void delete(String key) {
			files.remove(key);
		}
	}

	final MemoryStorage storage = new MemoryStorage();
	final ImageService service = new ImageService(storage);

	@Test
	void recognisesImageTypesByMagicBytes() throws Exception {
		assertThat(ImageService.isPng(png(10, 10))).isTrue();
		assertThat(ImageService.isJpeg(png(10, 10))).isFalse();
		assertThat(ImageService.isWebp("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isTrue();
		assertThat(ImageService.isWebp("RIFF\0\0\0\0WAVEfmt ".getBytes())).isFalse();
	}

	@Test
	void smallImagesAreNotEnlarged() throws Exception {
		StoredImage stored = service.store(file(png(300, 200)), Kind.DOCTOR_PHOTO);
		BufferedImage saved = ImageIO.read(new ByteArrayInputStream(storage.files.get(stored.key())));
		assertThat(saved.getWidth()).isEqualTo(300);
		assertThat(saved.getHeight()).isEqualTo(200);
		assertThat(stored.key()).matches("doctors/[0-9a-f]{32}\\.jpg");
		assertThat(stored.thumbKey()).isNull();
	}

	@Test
	void galleryImagesGetAThumbnail() throws Exception {
		StoredImage stored = service.store(file(png(3000, 1500)), Kind.GALLERY);
		assertThat(storage.files).containsOnlyKeys(stored.key(), stored.thumbKey());
		BufferedImage main = ImageIO.read(new ByteArrayInputStream(storage.files.get(stored.key())));
		BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(storage.files.get(stored.thumbKey())));
		assertThat(main.getWidth()).isEqualTo(1600);
		assertThat(thumb.getWidth()).isEqualTo(480);
		assertThat(stored.keys()).hasSize(2);
	}

	@Test
	void refusesDecompressionBombs() throws Exception {
		// A tiny PNG whose header claims 50000 x 50000 pixels: rejected before any pixels are decoded.
		byte[] bomb = withPngDimensions(png(1, 1), 50_000, 50_000);
		assertThatThrownBy(() -> service.store(file(bomb), Kind.GALLERY))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "IMAGE_TOO_LARGE");
		assertThat(storage.files).isEmpty();
	}

	@Test
	void truncatedImageIsRejected() throws Exception {
		byte[] full = png(200, 200);
		byte[] truncated = Arrays.copyOf(full, 60);   // valid header, pixel data cut off
		assertThatThrownBy(() -> service.store(file(truncated), Kind.GALLERY))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "INVALID_IMAGE");
	}

	@Test
	void cleanupOutsideTransactionDeletesReplacedFilesOnly() {
		storage.files.put("doctors/old.jpg", new byte[1]);
		storage.files.put("doctors/new.jpg", new byte[1]);
		service.cleanupOnCompletion(List.of("doctors/old.jpg", "/placeholders/branch.svg"), List.of("doctors/new.jpg"));
		assertThat(storage.files).containsOnlyKeys("doctors/new.jpg");
	}

	private static MockMultipartFile file(byte[] bytes) {
		return new MockMultipartFile("file", "x.png", "image/png", bytes);
	}

	private static byte[] png(int w, int h) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
		return out.toByteArray();
	}

	/** Rewrites the IHDR width/height (bytes 16-23) and fixes its CRC. */
	private static byte[] withPngDimensions(byte[] png, int width, int height) {
		byte[] copy = png.clone();
		ByteBuffer.wrap(copy, 16, 8).putInt(width).putInt(height);
		CRC32 crc = new CRC32();
		crc.update(copy, 12, 17);   // chunk type "IHDR" + 13 data bytes
		ByteBuffer.wrap(copy, 29, 4).putInt((int) crc.getValue());
		return copy;
	}

}
