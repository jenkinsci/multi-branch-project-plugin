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

import hudson.DescriptorExtensionList;
import hudson.scm.NullSCM;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.RequestImpl;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;

/**
 * Prevents configuration of {@link TemplateDrivenMultiBranchProject}s from bleeding into their template projects
 * when the request is passed to the template's
 * {@link hudson.model.AbstractProject#doConfigSubmit(StaplerRequest, StaplerResponse)} method.
 *
 * @author Matthew DeTullio
 */
public final class TemplateStaplerRequestWrapper extends RequestImpl {
    /**
     * Constructs this extension of {@link RequestImpl} under the assumption that {@link RequestImpl} is also the
     * underlying type of the {@link StaplerRequest}.
     *
     * @param request the request submitted the {@link TemplateDrivenMultiBranchProject}
     * @throws ServletException if errors
     */
    /*package*/ TemplateStaplerRequestWrapper(StaplerRequest request) throws ServletException {
        /*
         * Ugly casts to RequestImpl... but should be ok since it will throw
         * errors, which we want anyway if it's not that type.
         */
        super(request.getStapler(), request, ((RequestImpl) request).ancestors,
                ((RequestImpl) request).tokens);
    }

    /**
     * Overrides certain parameter names with certain values needed when setting the configuration for
     * template projects.  Otherwise, relies on the standard implementation.
     * <br>
     * {@inheritDoc}
     */
    @Override
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

        /*
         * Parameters for conflicting triggers should return null if the
         * corresponding JSON was not provided.  Otherwise, NPEs occur when
         * trying to update the triggers for the template project.
         */
        DescriptorExtensionList<Trigger<?>, TriggerDescriptor> triggerDescriptors = Trigger.all();
        for (TriggerDescriptor triggerDescriptor : triggerDescriptors) {
            String safeName = triggerDescriptor.getJsonSafeClassName();

            try {
                if (name.equals(safeName) && getSubmittedForm().getJSONObject(safeName).isNullObject()) {
                    return null;
                }
            } catch (ServletException e) {
                throw new IllegalStateException("Exception getting data from submitted JSON", e);
            }
        }

        // Fallback to standard functionality
        return super.getParameter(name);
    }

    /**
     * Overrides the form with a sanitized version.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public JSONObject getSubmittedForm() throws ServletException {
        JSONObject json = super.getSubmittedForm().getJSONObject("projectFactory");

        // JENKINS-36043: Provide dummy SCM since the form elements were removed from the config page
        // {"scm": {"value": "0", "stapler-class": "hudson.scm.NullSCM", "$class": "hudson.scm.NullSCM"}}
        JSONObject scm = new JSONObject();
        scm.put("value", "0");
        scm.put("stapler-class", NullSCM.class.getName());
        scm.put("$class", NullSCM.class.getName());

        json.put("scm", scm);
        return json;
    }
}
