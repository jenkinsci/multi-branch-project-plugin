package com.github.mjdetullio.jenkins.plugins.multibranch;

import hudson.scm.SCM;

import java.io.Serializable;
import java.lang.reflect.Field;

/**
 * @author Hiroyuki Wada
 */
public class SCMOptions implements Serializable {

	protected SCM scmTemplate;

	public SCMOptions(SCM scm) {
		this.scmTemplate = scm;
	}

	public SCM getSCMTemplate() {
		return scmTemplate;
	}

	public void apply(SCM scm) throws Exception {
		// Copy properties for all SCM
		apply(scm, "browser", scmTemplate.getBrowser());

		// Copy properties for GitSCM
		if (scm.getType().equals("hudson.plugins.git.GitSCM")) {
			apply(scm, "gitTool");
			apply(scm, "extensions");
		}
	}

	private void apply(SCM dest, String name, Object value) throws Exception {
		Field field = dest.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(dest, value);
	}

	private void apply(SCM dest, String name) throws Exception {
		Field source = scmTemplate.getClass().getDeclaredField(name);
		source.setAccessible(true);

		Field field = dest.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(dest, source.get(scmTemplate));
	}

	private static final long serialVersionUID = 1L;
}
