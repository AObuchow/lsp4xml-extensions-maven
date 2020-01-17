/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMElement;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.services.extensions.diagnostics.IDiagnosticsParticipant;

public class MavenDiagnosticParticipant implements IDiagnosticsParticipant {

	private Supplier<PlexusContainer> containerSupplier;
	private RepositoryCache repositoryCache = RepositoryCache.getInstance();

	public MavenDiagnosticParticipant(Supplier<PlexusContainer> containerSupplier) {
		this.containerSupplier = containerSupplier;
	}

	@Override
	public void doDiagnostics(DOMDocument xmlDocument, List<Diagnostic> diagnostics, CancelChecker monitor) {
		// TODO: This shouldn't be called on each doDiagnostics, only call when project cache is modified
		repositoryCache.update(xmlDocument);
		File workingCopy = null;
		try {
			ProjectBuilder projectBuilder = containerSupplier.get().lookup(ProjectBuilder.class);
			File file = new File(URI.create(xmlDocument.getDocumentURI()));
			workingCopy = File.createTempFile("workingCopy", '.' + file.getName(), file.getParentFile());
			Files.copy(file.toPath(), workingCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
			ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
			ProjectBuildingResult buildResult = projectBuilder.build(workingCopy, request);
			buildResult.getProblems().stream().map(this::toDiagnostic).forEach(diagnostics::add);
		} catch (ProjectBuildingException e) {
			if (e.getResults() == null) {
				if (e.getCause() instanceof ModelBuildingException) {
					ModelBuildingException modelBuildingException = (ModelBuildingException)e.getCause();
					modelBuildingException.getProblems().stream().map(this::toDiagnostic).forEach(diagnostics::add);
				} else {
					diagnostics.add(toDiagnostic(e));
				}
			} else {
				e.getResults().stream().flatMap(result -> result.getProblems().stream()).map(this::toDiagnostic).forEach(diagnostics::add);
			}
		} catch (ComponentLookupException | IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (workingCopy != null) {
			workingCopy.delete();
		}

		DOMElement documentElement = xmlDocument.getDocumentElement();
		HashMap<String, Function<DiagnosticRequest, Diagnostic>> tagDiagnostics = configureDiagnosticFunctions(
				xmlDocument);

		Deque<DOMNode> nodes = new ArrayDeque<>();
		for (DOMNode node : documentElement.getChildren()) {
			nodes.push(node);
		}
		while (!nodes.isEmpty()) {
			DOMNode node = nodes.pop();
			for (String tagToValidate : tagDiagnostics.keySet()) {
				if (node.getLocalName() != null && node.getLocalName().equals(tagToValidate)) {
					Diagnostic diagnostic = null;
					try {
						diagnostic = tagDiagnostics.get(tagToValidate)
								.apply(new DiagnosticRequest(node, xmlDocument, diagnostics));
					} catch (Exception e) {
						// TODO: Use plug-in error logger
						e.printStackTrace();
					}

					if (diagnostic != null) {
						diagnostics.add(diagnostic);
					}
				}
			}
			if (node.hasChildNodes()) {
				for (DOMNode childNode : node.getChildren()) {
					nodes.push(childNode);
				}
			}
		}
	}

	private HashMap<String, Function<DiagnosticRequest, Diagnostic>> configureDiagnosticFunctions(
			DOMDocument xmlDocument) {
		SubModuleValidator subModuleValidator= new SubModuleValidator();
		try {
			subModuleValidator.setPomFile(new File(xmlDocument.getDocumentURI().substring(5)));
		} catch (IOException | XmlPullParserException e) {
			// TODO: Use plug-in error logger
			e.printStackTrace();
		}
		Function<DiagnosticRequest, Diagnostic> versionFunc = VersionValidator::validateVersion;
		Function<DiagnosticRequest, Diagnostic> submoduleExistenceFunc = subModuleValidator::validateSubModuleExistence;
		// Below is a mock Diagnostic function which creates a warning between inside
		// <configuration> tags
		Function<DiagnosticRequest, Diagnostic> configFunc = diagnosticReq -> new Diagnostic(diagnosticReq.getRange(),
				"Configuration Error", DiagnosticSeverity.Warning, xmlDocument.getDocumentURI(), "XML");

		HashMap<String, Function<DiagnosticRequest, Diagnostic>> tagDiagnostics = new HashMap<>();
		tagDiagnostics.put("version", versionFunc);
		tagDiagnostics.put("configuration", configFunc);
		tagDiagnostics.put("module", submoduleExistenceFunc);
		return tagDiagnostics;
	}

	private Diagnostic toDiagnostic(@Nonnull ModelProblem problem) {
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setMessage(problem.getMessage());
		diagnostic.setSeverity(toDiagnosticSeverity(problem.getSeverity()));
		diagnostic.setRange(new Range(new Position(problem.getLineNumber(), problem.getColumnNumber()), new Position(problem.getLineNumber(), problem.getColumnNumber() + 1)));
		return diagnostic;
	}

	private DiagnosticSeverity toDiagnosticSeverity(Severity severity) {
		switch (severity) {
		case ERROR:
		case FATAL:
			return DiagnosticSeverity.Error;
		case WARNING:
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Information;
	}


	private Diagnostic toDiagnostic(Exception e) {
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setMessage(e.getMessage());
		diagnostic.setSeverity(DiagnosticSeverity.Error);
		diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 0)));
		return diagnostic;
	}
}
