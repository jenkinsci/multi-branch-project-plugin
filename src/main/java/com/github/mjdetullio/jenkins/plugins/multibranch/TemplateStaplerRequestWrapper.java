/*
 * The MIT License
 *
 * Copyright (c) 2014, Matthew DeTullio
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

import javax.servlet.ServletException;

import org.kohsuke.stapler.RequestImpl;
import org.kohsuke.stapler.StaplerRequest;

import net.sf.json.JSONObject;

/**
 * @author Matthew DeTullio
 */
public class TemplateStaplerRequestWrapper extends RequestImpl {
	public TemplateStaplerRequestWrapper(StaplerRequest request)
			throws ServletException {
		/*
		 * Ugly casts to RequestImpl... but should be ok since it will throw
		 * errors, which we want anyway if it's not that type.
		 */
		super(request.getStapler(), request, ((RequestImpl) request).ancestors,
				((RequestImpl) request).tokens);

		// Remove some fields that we don't want to send to the template
		JSONObject json = getSubmittedForm();
		json.remove("name");
		json.remove("description");
		json.remove("displayNameOrNull");
	}

	/**
	 * Overrides certain parameter names with certain values needed when setting
	 * the configuration for template projects.  Otherwise, relies on the
	 * standard implementation. <p/> Inherited docs: <p/> {@inheritDoc}
	 */
	public String getParameter(String name) {
		// Sanitize the following parameters
		if ("name".equals(name)) {
			// Don't set the name
			return null;
		} else if ("description".equals(name)) {
			// Don't set the description
			return null;
		} else if ("disable".equals(name)) {
			// Mark disabled
			return "";
		}

		// Fallback to standard functionality
		return super.getParameter(name);
	}
}
