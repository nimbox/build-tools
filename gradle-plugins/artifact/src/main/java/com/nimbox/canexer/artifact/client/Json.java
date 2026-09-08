package com.nimbox.canexer.artifact.client;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;

/**
 * JSON in and out of the manager plane and the tower, as plain maps and
 * lists. Numbers read back as {@link Double}.
 */
public final class Json {

	private static final Moshi MOSHI = new Moshi.Builder().build();
	private static final Type OBJECT = Types.newParameterizedType(Map.class, String.class, Object.class);
	private static final Type ARRAY = Types.newParameterizedType(List.class, Object.class);
	private static final JsonAdapter<Map<String, Object>> OBJECT_ADAPTER = MOSHI.adapter(OBJECT);
	private static final JsonAdapter<List<Object>> ARRAY_ADAPTER = MOSHI.adapter(ARRAY);
	private static final JsonAdapter<Object> ANY_ADAPTER = MOSHI.adapter(Object.class).serializeNulls();

	private Json() {
	}

	/** @return the object, or null when the text is not a JSON object */
	public static Map<String, Object> object(String text) {

		if (text == null || text.isBlank()) {
			return null;
		}
		
		try {
			return OBJECT_ADAPTER.fromJson(text);
		} catch (IOException | RuntimeException e) {
			return null;
		}

	}

	/** @throws BoxClientException when the text is not a JSON array */
	public static List<Object> array(String text, String source) {
		
		try {
			List<Object> array = ARRAY_ADAPTER.fromJson(text);
			if (array == null) {
				throw new BoxClientException(source + " is not a JSON array");
			}
			return array;
		} catch (IOException | RuntimeException e) {
			throw new BoxClientException(source + " is not a JSON array: " + e.getMessage(), e);
		}

	}

	public static String write(Object value) {
		return ANY_ADAPTER.toJson(value);
	}

	public static String pretty(Object value) {
		return ANY_ADAPTER.indent("  ").toJson(value);
	}

	/** A string field of an object, or null. */
	public static String string(Map<String, Object> object, String key) {
		
		if (object == null) {
			return null;
		}
		
		Object value = object.get(key);
		return value == null ? null : value.toString();

	}

	/** A nested object, or null. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(Map<String, Object> object, String key) {
	
		if (object == null) {
			return null;
		}

		Object value = object.get(key);
		return value instanceof Map ? (Map<String, Object>) value : null;
	
	}

	/** A nested array, or an empty list. */
	@SuppressWarnings("unchecked")
	public static List<Object> array(Map<String, Object> object, String key) {

		if (object == null) {
			return List.of();
		}

		Object value = object.get(key);
		return value instanceof List ? (List<Object>) value : List.of();

	}

}

