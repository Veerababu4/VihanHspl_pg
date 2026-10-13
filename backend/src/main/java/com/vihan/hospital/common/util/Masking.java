package com.vihan.hospital.common.util;

/** Masks personal data for logs, exports and display (DESIGN.md §10.7, §12.4). */
public final class Masking {

	private Masking() {
	}

	/** {@code 9876543221 -> 98XXXXXX21}. Anything that isn't a 10-digit number is fully masked. */
	public static String mobile(String mobile) {
		if (mobile == null || !mobile.matches("\\d{10}")) {
			return "XXXXXXXXXX";
		}
		return mobile.substring(0, 2) + "XXXXXX" + mobile.substring(8);
	}

	/** {@code Ravi Kumar -> R*** K***}: first letter of each word only. */
	public static String name(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (String word : name.strip().split("\\s+")) {
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.appendCodePoint(word.codePointAt(0)).append("***");
		}
		return sb.toString();
	}

	/** {@code VMH-261013-7K3Q -> VMH-261013-****}: keeps the date part, hides the random part. */
	public static String bookingRef(String ref) {
		if (ref == null) {
			return null;
		}
		int last = ref.lastIndexOf('-');
		return last < 0 ? "****" : ref.substring(0, last + 1) + "****";
	}

	/** {@code 1234 -> XXXX-XXXX-1234}; null when the patient gave no Aadhaar. */
	public static String aadhaarFromLast4(String last4) {
		return last4 == null ? null : "XXXX-XXXX-" + last4;
	}

}
