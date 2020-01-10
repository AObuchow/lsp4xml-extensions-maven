/**
 *  Copyright (c) 2018 Angelo ZERR
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v2.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v20.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.lsp4xml.extensions.maven;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.services.extensions.ICompletionParticipant;
import org.eclipse.lsp4xml.services.extensions.IXMLExtension;
import org.eclipse.lsp4xml.services.extensions.XMLExtensionsRegistry;
import org.eclipse.lsp4xml.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lsp4xml.services.extensions.save.ISaveContext;

/**
 * Extension for pom.xml.
 *
 */
public class MavenPlugin implements IXMLExtension {

	private static final String POM_XML = "pom.xml";

	private final ICompletionParticipant completionParticipant;
	private final IDiagnosticsParticipant diagnosticParticipant;
	public MavenPlugin() {
		completionParticipant = new MavenCompletionParticipant();
		diagnosticParticipant = new MavenDiagnosticParticipant();
	}

	@Override
	public void doSave(ISaveContext context) {

	}

	@Override
	public void start(InitializeParams params, XMLExtensionsRegistry registry) {
		registry.registerCompletionParticipant(completionParticipant);
		registry.registerDiagnosticsParticipant(diagnosticParticipant);
	}

	@Override
	public void stop(XMLExtensionsRegistry registry) {
		registry.unregisterCompletionParticipant(completionParticipant);
		registry.unregisterDiagnosticsParticipant(diagnosticParticipant);
	}

	public static boolean match(DOMDocument document) {
		return document.getDocumentURI().endsWith(POM_XML);
	}
}
