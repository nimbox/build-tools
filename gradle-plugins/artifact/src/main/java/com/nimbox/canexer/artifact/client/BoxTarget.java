package com.nimbox.canexer.artifact.client;

/**
 * Where a request goes: a box and its server URL. A named box is reached at
 * the {@code url} of its descriptor, never at a default, since a missing url
 * would send the request to the wrong box. Without a box the URL is the
 * override ({@code CANEXER_URL}) or localhost. An explicit secret, when
 * given, is presented as the bearer and the tower is never asked.
 */
public final class BoxTarget {

	public static final String DEFAULT_URL = "http://localhost:8088";

	private final String box;
	private final String url;
	private final String secret;

	private BoxTarget(String box, String url, String secret) {
		this.box = box;
		this.url = url;
		this.secret = secret;
	}

	/**
	 * @param box the box name, or null for none
	 * @param urlOverride the server URL when no box is named, or null
	 * @param secret an explicit bearer, or null
	 */
	public static BoxTarget resolve(String box, String urlOverride, String secret) {
		String url;
		if (box != null && !box.isBlank()) {
			BoxDescriptor descriptor = BoxDescriptor.load(box);
			url = descriptor.url();
			if (url == null) {
				throw new BoxClientException("box '" + box + "' has no url in its descriptor " + descriptor.manifestFile() + "; add one, or name no box to install on " + (urlOverride != null && !urlOverride.isBlank() ? urlOverride : DEFAULT_URL));
			}
		} else {
			box = null;
			url = urlOverride != null && !urlOverride.isBlank() ? urlOverride : DEFAULT_URL;
		}
		return new BoxTarget(box, url.replaceAll("/+$", ""), secret != null && !secret.isBlank() ? secret : null);
	}

	/** A target whose URL is known, for tests and for a descriptor already loaded. */
	public static BoxTarget of(String box, String url) {
		return new BoxTarget(box, url.replaceAll("/+$", ""), null);
	}

	/** @return the box name, or null when none was named */
	public String box() {
		return box;
	}

	public String url() {
		return url;
	}

	/** @return the explicit bearer, or null */
	public String secret() {
		return secret;
	}

	/** The target for the log: the box and where it is, or just where. */
	public String describe() {
		return box == null ? url : "box '" + box + "' at " + url;
	}

}
