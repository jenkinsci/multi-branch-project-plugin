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

import org.kohsuke.stapler.ResponseImpl;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Prevents the template project from committing a redirect when it finishes handling the request in
 * {@link hudson.model.AbstractProject#doConfigSubmit(StaplerRequest, StaplerResponse)}.
 *
 * @author Matthew DeTullio
 */
public final class TemplateStaplerResponseWrapper extends ResponseImpl {
    /**
     * Constructs this extension of {@link ResponseImpl} using the provided {@link Stapler} instance and the
     * {@link StaplerResponse} you want to wrap.
     *
     * @param stapler the Stapler instance, which you can get from {@link StaplerRequest#getStapler()}
     * @param response the response you want to wrap
     * @throws ServletException
     */
    /*package*/ TemplateStaplerResponseWrapper(Stapler stapler, StaplerResponse response) throws ServletException {
        super(stapler, response);
    }

    /**
     * No-op.
     *
     * @param url ignored
     * @throws IOException impossible
     */
    @Override
    public void sendRedirect(@Nonnull String url) throws IOException {
        // No-op
    }

    /**
     * No-op.
     *
     * @param var1 ignored
     * @param var2 ignored
     * @throws IOException impossible
     */
    @Override
    public void sendRedirect(int var1, @Nonnull String var2) throws IOException {
        // No-op
    }

    /**
     * No-op.
     *
     * @param var1 ignored
     * @throws IOException impossible
     */
    @Override
    public void sendRedirect2(@Nonnull String var1) throws IOException {
        // No-op
    }
}
