package com.nimbox.canexer.artifact.client;

/**
 * A box could not be reached, refused a request, or its descriptor is not
 * usable. The message is what the developer reads; Gradle prints it as the
 * failure cause.
 */
public class BoxClientException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public BoxClientException(String message) {
		super(message);
	}

	public BoxClientException(String message, Throwable cause) {
		super(message, cause);
	}

}
