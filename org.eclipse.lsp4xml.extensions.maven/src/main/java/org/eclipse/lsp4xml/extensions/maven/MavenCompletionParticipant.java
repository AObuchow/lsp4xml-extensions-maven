/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4xml.commons.BadLocationException;
import org.eclipse.lsp4xml.commons.TextDocument;
import org.eclipse.lsp4xml.commons.snippets.SnippetRegistry;
import org.eclipse.lsp4xml.dom.DOMDocument;
import org.eclipse.lsp4xml.dom.DOMElement;
import org.eclipse.lsp4xml.dom.DOMNode;
import org.eclipse.lsp4xml.dom.LineIndentInfo;
import org.eclipse.lsp4xml.extensions.maven.searcher.ArtifactSearcherManager;
import org.eclipse.lsp4xml.extensions.maven.searcher.LocalRepositorySearcher;
import org.eclipse.lsp4xml.extensions.maven.searcher.RemoteRepositoryIndexSearcher;
import org.eclipse.lsp4xml.services.extensions.CompletionParticipantAdapter;
import org.eclipse.lsp4xml.services.extensions.ICompletionRequest;
import org.eclipse.lsp4xml.services.extensions.ICompletionResponse;
import org.eclipse.lsp4xml.utils.XMLPositionUtility;

public class MavenCompletionParticipant extends CompletionParticipantAdapter {

	private boolean snippetsLoaded;
	private final LocalRepositorySearcher localRepositorySearcher = new LocalRepositorySearcher();
	private final MavenProjectCache cache;
	private CompletableFuture<Void> indexSyncRequest;
	RemoteRepositoryIndexSearcher artifactVersionSearcher;

	public MavenCompletionParticipant(MavenProjectCache cache) {
		this.cache = cache;
		try {
			artifactVersionSearcher = new RemoteRepositoryIndexSearcher(cache.getPlexusContainer());
			indexSyncRequest = artifactVersionSearcher.getSyncRequest();
			indexSyncRequest.get(500, TimeUnit.MILLISECONDS);
		} catch (ComponentLookupException | InterruptedException | ExecutionException | TimeoutException e) {
			e.printStackTrace();
		}
	}

	// TODO: Move this up into MavenPlugin as the Maven index will likely be
	// required for other classes eg. MavenDiagnosticParticipant
	public void closeIndexContext() {
		artifactVersionSearcher.closeContext();
	}

	@Override
	public void onXMLContent(ICompletionRequest request, ICompletionResponse response) throws Exception {
		DOMElement parent = request.getParentElement();

		if (parent == null || parent.getLocalName() == null) {
			return;
		}
		// TODO: These two switch cases should be combined into one
		if (parent.getParentElement() != null) {
			switch (parent.getParentElement().getLocalName()) {
			case "parent":
				collectParentCompletion(request, response);
				break;
			case "plugin":
				break;
			case "dependency":
				collectionRemoteGAVCompletion(request, response);
				break;
			default:
				break;
			}
		}
		switch (parent.getLocalName()) {
		case "scope":
			collectSimpleCompletionItems(Arrays.asList(DependencyScope.values()), DependencyScope::getName,
					DependencyScope::getDescription, request, response);
			break;
		case "phase":
			collectSimpleCompletionItems(Arrays.asList(Phase.ALL_STANDARD_PHASES), phase -> phase.id,
					phase -> phase.description, request, response);
			break;
		case "groupId":
			if (!parent.getParentElement().getLocalName().equals("parent")) {
				collectSimpleCompletionItems(ArtifactSearcherManager.getInstance().searchLocalGroupIds(null),
						Function.identity(), Function.identity(), request, response);
			}
			break;
		case "module":
			collectSubModuleCompletion(request, response);
			break;
		case "dependencies":
			collectLocalArtifacts(request, response);
			// Break commented out for now so that snippets can be available
			// break;
		default:
			initSnippets();
			TextDocument document = parent.getOwnerDocument().getTextDocument();
			int completionOffset = request.getOffset();
			boolean canSupportMarkdown = true; // request.canSupportMarkupKind(MarkupKind.MARKDOWN);
			SnippetRegistry.getInstance()
					.getCompletionItems(document, completionOffset, canSupportMarkdown, context -> {
						if (!"pom.xml".equals(context.getType())) {
							return false;
						}
						return parent.getLocalName().equals(context.getValue());
					}).forEach(completionItem -> response.addCompletionItem(completionItem));
		}
		if (request.getNode().isText()) {
			completeProperties(request, response);
		}
	}

	private void collectLocalArtifacts(ICompletionRequest request, ICompletionResponse response) {
		try {
			Map<Entry<String, String>, ArtifactVersion> groupIdArtifactIdToVersion = localRepositorySearcher
					.getLocalArtifacts(RepositorySystem.defaultUserLocalRepository);
			final DOMDocument xmlDocument = request.getXMLDocument();
			final int requestOffset = request.getOffset();
			int insertionOffset = requestOffset;
			while (insertionOffset > 0 && Character.isAlphabetic(xmlDocument.getText().charAt(insertionOffset - 1))) {
				insertionOffset--;
			}
			while (insertionOffset > 0 && xmlDocument.getText().charAt(insertionOffset - 1) != '\n') {
				insertionOffset--;
			}
			final int theInsertionOffset = insertionOffset;
			DOMElement parentElement = request.getParentElement();
			String indent = "\t";
			String lineDelimiter = "\n";
			try {
				LineIndentInfo lineIndentInfo = xmlDocument
						.getLineIndentInfo(xmlDocument.positionAt(parentElement.getStart()).getLine());
				indent = lineIndentInfo.getWhitespacesIndent();
				lineDelimiter = lineIndentInfo.getLineDelimiter();
			} catch (BadLocationException ex) {

			}
			StringBuilder refIndentBuilder = new StringBuilder();
			while (parentElement != null) {
				refIndentBuilder.append(indent);
				parentElement = parentElement.getParentElement();
			}
			final String indentString = indent;
			final String refIndent = refIndentBuilder.toString();
			final String delim = lineDelimiter;
			groupIdArtifactIdToVersion.forEach((groupIdArtifactId, version) -> {
				CompletionItem item = new CompletionItem();
				item.setLabel(groupIdArtifactId.getValue() + " - " + groupIdArtifactId.getKey() + ':'
						+ groupIdArtifactId.getValue());
				// TODO: deal with indentation
				try {
					item.setTextEdit(new TextEdit(
							new Range(xmlDocument.positionAt(theInsertionOffset),
									xmlDocument.positionAt(requestOffset)),
							refIndent + "<dependency>" + delim + refIndent + indentString + "<groupId>"
									+ groupIdArtifactId.getKey() + "</groupId>" + delim + refIndent + indentString
									+ "<artifactId>" + groupIdArtifactId.getValue() + "</artifactId>" + delim
									+ refIndent + indentString + "<version>" + version.toString() + "</version>" + delim
									+ refIndent + "</dependency>" + delim + refIndent));
				} catch (BadLocationException e) {
					e.printStackTrace();
				}
				item.setDocumentation("From local repository\n\n" + item.getTextEdit().getNewText());
				response.addCompletionItem(item, false);
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void completeProperties(ICompletionRequest request, ICompletionResponse response) {
		DOMDocument xmlDocument = request.getXMLDocument();
		String documentText = xmlDocument.getText();
		int initialPropertyOffset = request.getOffset();
		for (int i = request.getOffset() - 1; i >= request.getNode().getStart(); i--) {
			char currentChar = documentText.charAt(i);
			if (currentChar == '}') {
				// properties area ended, return all properties
				break;
			} else if (currentChar == '$') {
				initialPropertyOffset = i;
				break;
			}
		}
		Map<String, String> allProps = new HashMap<>();
		MavenProject project = cache.getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project != null && project.getProperties() != null) {
			for (Entry<Object, Object> prop : project.getProperties().entrySet()) {
				allProps.put((String) prop.getKey(), (String) prop.getValue());
			}
		}
		allProps.put("basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.basedir", project == null ? "unknown" : project.getBasedir().toString());
		allProps.put("project.version", project == null ? "unknown" : project.getVersion());
		allProps.put("project.groupId", project == null ? "unknown" : project.getGroupId());
		allProps.put("project.artifactId", project == null ? "unknown" : project.getArtifactId());
		allProps.put("project.name", project == null ? "unknown" : project.getName());
		allProps.put("project.build.directory", project == null ? "unknown" : project.getBuild().getDirectory());
		allProps.put("project.build.outputDirectory",
				project == null ? "unknown" : project.getBuild().getOutputDirectory());

		for (Entry<String, String> property : allProps.entrySet()) {
			CompletionItem item = new CompletionItem();
			item.setLabel("${" + property.getKey() + '}');
			item.setDocumentation("Default Value: " + (property.getValue() != null ? property.getValue() : "unknown"));
			try {
				TextEdit textEdit = new TextEdit();
				textEdit.setNewText(item.getLabel());
				Range range = new Range(xmlDocument.positionAt(initialPropertyOffset),
						xmlDocument.positionAt(request.getOffset()));
				textEdit.setRange(range);
				item.setTextEdit(textEdit);
			} catch (BadLocationException e) {
				e.printStackTrace();
				item.setInsertText(item.getLabel());
			}
			response.addCompletionItem(item, false);
		}
	}

	private void collectionRemoteGAVCompletion(ICompletionRequest request, ICompletionResponse response) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);

		Artifact artifactToSearch = VersionValidator.parseArtifact(node);
		if (indexSyncRequest.isDone()) {
			try {
				switch (node.getLocalName()) {
				case "groupId":
					if (artifactToSearch.getGroupId().equals(VersionValidator.placeholderArtifact.getGroupId())) {
						// Don't do a remote groupId search if the user hasn't inputed anything as there
						// will be too many results
						return;
					}
					for (String groupId : artifactVersionSearcher.getGroupIds(artifactToSearch).get()) {
						response.addCompletionItem(toCompletionItem(groupId, "GroupId", range));
					}
					break;
				case "artifactId":
					for (Map.Entry<String, String> entry : artifactVersionSearcher.getArtifactIds(artifactToSearch)
							.get().entrySet()) {
						response.addCompletionItem(toCompletionItem(entry.getKey(), entry.getValue(), range));
					}
					break;
				case "version":
					for (String version : artifactVersionSearcher.getArtifactVersions(artifactToSearch).get()) {
						response.addCompletionItem(toCompletionItem(version, "Artifact Version", range));
					}
					break;
				}
			} catch (InterruptedException e) {
				response.addCompletionItem(toCompletionItem("Error: Remote index search interrupted", "Error", range));
				e.printStackTrace();
			} catch (ExecutionException e) {
				response.addCompletionItem(
						toCompletionItem("Error: Remote index search error occured", "Error", range));
				e.printStackTrace();
			}
		} else {
			response.addCompletionItem(toCompletionItem("Updating Maven repository index...",
					"Maven repository index update in progress", range));
		}

	}

	private void collectSubModuleCompletion(ICompletionRequest request, ICompletionResponse response) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);
		MavenProject mavenProject = cache.getLastSuccessfulMavenProject(doc);
		if (mavenProject == null) {
			return;
		}
		Model model = mavenProject.getModel();
		for (String module : model.getModules()) {
			response.addCompletionItem(toCompletionItem(module, "", range));
		}

	}

	private void initSnippets() {
		if (snippetsLoaded) {
			return;
		}
		try {
			try {
				SnippetRegistry.getInstance()
						.load(MavenCompletionParticipant.class.getResourceAsStream("pom-snippets.json"));
			} catch (IOException e) {
				e.printStackTrace();
			}
		} finally {
			snippetsLoaded = true;
		}

	}

	private void collectParentCompletion(ICompletionRequest request, ICompletionResponse response) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();
		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);
		MavenProject mavenProject = cache.getLastSuccessfulMavenProject(doc);
		if (mavenProject == null) {
			return;
		}
		Model model = mavenProject.getModel();

		switch (node.getLocalName()) {
		case "artifactId":
			response.addCompletionItem(toCompletionItem(model.getParent().getArtifactId(),
					"The artifactId of the parent maven module.", range));
			break;
		case "groupId":
			response.addCompletionItem(
					toCompletionItem(model.getParent().getGroupId(), "The groupId of the parent maven module.", range));
			break;
		case "version":
			response.addCompletionItem(
					toCompletionItem(model.getParent().getVersion(), "The version of the parent maven module.", range));
			break;
		default:
			break;
		}

	}

	private <T> void collectSimpleCompletionItems(Collection<T> items, Function<T, String> insertionTextExtractor,
			Function<T, String> documentationExtractor, ICompletionRequest request, ICompletionResponse response) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();
		boolean needClosingTag = node.getEndTagOpenOffset() == DOMNode.NULL_VALUE;
		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1,
				needClosingTag ? node.getStartTagOpenOffset() + 1 : node.getEndTagOpenOffset(), doc);

		for (T o : items) {
			String label = insertionTextExtractor.apply(o);
			CompletionItem item = new CompletionItem();
			item.setLabel(label);
			String insertText = label + (needClosingTag ? "</" + node.getTagName() + ">" : "");
			item.setKind(CompletionItemKind.Property);
			item.setDocumentation(Either.forLeft(documentationExtractor.apply(o)));
			item.setFilterText(insertText);
			item.setTextEdit(new TextEdit(range, insertText));
			item.setInsertTextFormat(InsertTextFormat.PlainText);
			response.addCompletionItem(item);
		}
	}

	/**
	 * Utility function, takes a label string, description and range and returns a
	 * CompletionItem
	 * 
	 * @param description Completion description
	 * @param label       Completion label
	 * @return CompletionItem resulting from the label, description and range given
	 * @param range Range where the completion will be inserted
	 */
	private static CompletionItem toCompletionItem(String label, String description, Range range) {
		CompletionItem item = new CompletionItem();
		item.setLabel(label);
		item.setSortText(label);
		item.setKind(CompletionItemKind.Property);
		String insertText = label;
		item.setDocumentation(Either.forLeft(description));
		item.setFilterText(insertText);
		item.setInsertTextFormat(InsertTextFormat.PlainText);
		item.setTextEdit(new TextEdit(range, insertText));
		return item;
	}
}
