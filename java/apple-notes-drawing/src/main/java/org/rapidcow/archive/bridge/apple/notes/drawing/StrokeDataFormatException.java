/*
 * Copyright 2026 rapidcow!
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package org.rapidcow.archive.bridge.apple.notes.drawing;

/**
 * Non-protobuf exception in the {@code StrokeData} message.
 */
public class StrokeDataFormatException extends RuntimeException {
	private static final long serialVersionUID = -883716016718836540L;

	/**
	 * Creates a new exception with the given message.
	 *
	 * @param message error message
	 */
	public StrokeDataFormatException(String message) {
		super(message);
	}
}
