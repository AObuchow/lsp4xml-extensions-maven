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

import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;
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

	private static final String MAVEN_XMLLS_EXTENSION_REALM_ID = MavenPlugin.class.getName();
	
	public static final String DEFAULT_LOCAL_REPOSITORY_PATH = System.getProperty("user.home") + "/.m2/repository";

	private final ICompletionParticipant completionParticipant;
	private final IDiagnosticsParticipant diagnosticParticipant;
	private PlexusContainer container;

	public MavenPlugin() {
		completionParticipant = new MavenCompletionParticipant(() -> this.container);
		diagnosticParticipant = new MavenDiagnosticParticipant(() -> this.container);
	}

	@Override public void doSave(ISaveContext context) {

	}

	@Override public void start(InitializeParams params, XMLExtensionsRegistry registry) {
		try {
			container = newPlexusContainer();
		} catch (PlexusContainerException e) {
			e.printStackTrace();
		}
		registry.registerCompletionParticipant(completionParticipant);
		registry.registerDiagnosticsParticipant(diagnosticParticipant);
	}

	/* Copied from m2e */
	private static DefaultPlexusContainer newPlexusContainer() throws PlexusContainerException {
		final ClassWorld classWorld = new ClassWorld(MAVEN_XMLLS_EXTENSION_REALM_ID, ClassWorld.class.getClassLoader());
		final ClassRealm realm;
		try {
			realm = classWorld.getRealm(MAVEN_XMLLS_EXTENSION_REALM_ID);
		} catch (NoSuchRealmException e) {
			throw new PlexusContainerException("Could not lookup required class realm", e);
		}
		final ContainerConfiguration mavenCoreCC = new DefaultContainerConfiguration() //
				.setClassWorld(classWorld) //
				.setRealm(realm) //
				.setClassPathScanning(PlexusConstants.SCANNING_INDEX) //
				.setAutoWiring(true) //
				.setName("mavenCore"); //$NON-NLS-1$

		// final Module logginModule = new AbstractModule() {
		// protected void configure() {
		// bind(ILoggerFactory.class).toInstance(LoggerFactory.getILoggerFactory());
		// }
		// };
		// final Module coreExportsModule = new AbstractModule() {
		// protected void configure() {
		// ClassRealm realm = mavenCoreCC.getRealm();
		// CoreExtensionEntry entry = CoreExtensionEntry.discoverFrom(realm);
		// CoreExports exports = new CoreExports(entry);
		// bind(CoreExports.class).toInstance(exports);
		// }
		// };
		return new DefaultPlexusContainer(mavenCoreCC);
	}

	@Override public void stop(XMLExtensionsRegistry registry) {
		registry.unregisterCompletionParticipant(completionParticipant);
		registry.unregisterDiagnosticsParticipant(diagnosticParticipant);
		container = null;
	}

	public static boolean match(DOMDocument document) {
		return document.getDocumentURI().endsWith(POM_XML);
	}
}
