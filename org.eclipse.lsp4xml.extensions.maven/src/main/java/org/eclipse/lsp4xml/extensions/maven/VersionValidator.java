package org.eclipse.lsp4xml.extensions.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMNode;

public class VersionValidator {

	private static Artifact setArtifact(DOMNode node) {
		String groupId = null;
		String artifactId = null;
		String version = null;
		String scope = "compile"; // Default scope if no scope is specified
		String type = "jar"; // Default type is jar if no type is specified
		String classifier = null; // Default classifier is null
		for (DOMNode tag : node.getParentElement().getChildren()) {
			switch (tag.getLocalName()) {
			case "groupId":
				groupId = tag.getChild(0).getNodeValue();
				break;
			case "artifactId":
				artifactId = tag.getChild(0).getNodeValue();
				break;
			case "version":
				version = tag.getChild(0).getNodeValue();
				break;
			case "scope":
				scope = tag.getChild(0).getNodeValue();
				break;
			case "type":
				type = tag.getChild(0).getNodeValue();
				break;
			case "classifier":
				tag.getChild(0).getNodeValue();
				break;
			}

		}
		return new DefaultArtifact(groupId, artifactId, version, scope, type, classifier,
				new DefaultArtifactHandler(type));
	}

	public static Diagnostic validateVersion(DiagnosticRequest diagnosticRequest) {
		DOMNode node = diagnosticRequest.getNode();
		DOMDocument xmlDocument = diagnosticRequest.getDOMDocument();
		Artifact artifact = setArtifact(node);
		Diagnostic diagnostic = null;
		Range range = diagnosticRequest.getRange();
		try {
			// TODO: This class doesn't work as intended - isSelectedVersionKnown() doesn't
			// actually verify the version is legal
			if (!artifact.isSelectedVersionKnown()) {
				diagnostic = new Diagnostic(range, "Version Error", DiagnosticSeverity.Error,
						xmlDocument.getDocumentURI(), "XML");
				diagnosticRequest.getDiagnostics().add(diagnostic);
			}
		} catch (OverConstrainedVersionException e) {
			// TODO: Use plug-in error logger
			e.printStackTrace();
		}
		return diagnostic;
	}
}
