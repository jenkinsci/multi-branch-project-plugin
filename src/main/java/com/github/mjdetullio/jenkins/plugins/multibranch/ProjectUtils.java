/*
 * The MIT License
 *
 * Copyright (c) 2014, Matthew DeTullio, Stephen Connolly
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.mjdetullio.jenkins.plugins.multibranch;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

/**
 * Utility methods for multi-branch projects.
 *
 * @author Matthew DeTullio
 */
public final class ProjectUtils {
	private ProjectUtils() {
		// Prevent outside instantiation
	}

	/**
	 * Inverse function of {@link hudson.Util#rawEncode(String)}. <p/> Kanged
	 * from Branch API.
	 *
	 * @param s the encoded string.
	 * @return the decoded string.
	 */
	public static String rawDecode(String s) {
		final byte[] bytes; // should be US-ASCII but we can be tolerant
		try {
			bytes = s.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(
					"JLS specification mandates UTF-8 as a supported encoding",
					e);
		}

		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		for (int i = 0; i < bytes.length; i++) {
			final int b = bytes[i];
			if (b == '%' && i + 2 < bytes.length) {
				final int u = Character.digit((char) bytes[++i], 16);
				final int l = Character.digit((char) bytes[++i], 16);

				if (u != -1 && l != -1) {
					buffer.write((char) ((u << 4) + l));
					continue;
				}

				// should be a valid encoding but we can be tolerant
				i -= 2;
			}
			buffer.write(b);
		}

		try {
			return new String(buffer.toByteArray(), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(
					"JLS specification mandates UTF-8 as a supported encoding",
					e);
		}
	}
}
