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
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.apache.maven.Maven;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
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
import org.jsoup.nodes.Node;

import com.google.common.base.Predicate;

public class MavenCompletionParticipant extends CompletionParticipantAdapter {

	private boolean snippetsLoaded;
	private final LocalRepositorySearcher localRepositorySearcher = new LocalRepositorySearcher();
	private final MavenProjectCache cache;
	private final RemoteRepositoryIndexSearcher indexSearcher;
	private MavenPluginManager pluginManager;

	public MavenCompletionParticipant(MavenProjectCache cache, RemoteRepositoryIndexSearcher indexSearcher, MavenPluginManager pluginManager) {
		this.cache = cache;
		this.indexSearcher = indexSearcher;
		this.pluginManager = pluginManager;
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
				collectRemotePluginGAVCompletion(request, response);
				break;
			case "dependency":
				collectRemoteGAVCompletion(request, response);
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
			if (!parent.getParentElement().getLocalName().equals("parent") && !parent.getParentElement().getLocalName().equals("plugin")) {
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
		case "goal":
			collectGoals(request, response);
			break;
		case "configuration":
			collectPluginConfigurationElements(request, response);
			break;
		default:
			initSnippets();
			TextDocument document = parent.getOwnerDocument().getTextDocument();
			int completionOffset = request.getOffset();
			boolean canSupportMarkdown = true; // request.canSupportMarkupKind(MarkupKind.MARKDOWN);
			SnippetRegistry.getInstance()
					.getCompletionItems(document, completionOffset, canSupportMarkdown, context -> {
						if (!Maven.POMv4.equals(context.getType())) {
							return false;
						}
						return parent.getLocalName().equals(context.getValue());
					}).forEach(completionItem -> response.addCompletionItem(completionItem));
		}
		if (request.getNode().isText()) {
			completeProperties(request, response);
		}
	}

	private void collectGoals(ICompletionRequest request, ICompletionResponse response) {
		PluginDescriptor pluginDescriptor = getContainingPluginDescriptor(request);
		if (pluginDescriptor != null) {
			collectSimpleCompletionItems(pluginDescriptor.getMojos(), MojoDescriptor::getGoal, MojoDescriptor::getDescription, request, response);
		}
	}
	private PluginDescriptor getContainingPluginDescriptor(ICompletionRequest request) {
		MavenProject project = cache.getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project == null) {
			return null;
		}
		DOMNode pluginNode = findClosestParentNode(request, "plugin");
		if (pluginNode == null) {
			return null;
		}
		Optional<String> groupId = findChildElementText(pluginNode, "groupId");
		Optional<String> artifactId = findChildElementText(pluginNode, "artifactId");
		String pluginKey = "";
		if (groupId.isPresent()) {
			pluginKey += groupId.get();
			pluginKey += ':';
		}
		if (artifactId.isPresent()) {
			pluginKey += artifactId.get();
		}
		Plugin plugin = project.getPlugin(pluginKey);
		if (plugin == null) {
			return null;
		}
		
		try {
			return pluginManager.getPluginDescriptor(plugin, project.getPluginRepositories().stream().map(this::toRemoteRepo).collect(Collectors.toList()), this.cache.getRepositorySystemSession());
		} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException e) {
			e.printStackTrace();
			return null;
		}
	}

	private Optional<String> findChildElementText(DOMNode pluginNode, final String elementName) {
		return pluginNode.getChildren().stream()
				.filter(node -> elementName.equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream())
				.findAny()
				.map(DOMNode::getTextContent)
				.map(String::trim);
	}

	private RemoteRepository toRemoteRepo(Repository modelRepo) {
		Builder builder = new RemoteRepository.Builder(modelRepo.getId(), modelRepo.getLayout(), modelRepo.getLayout());
		return builder.build();
		
	}

	private DOMNode findClosestParentNode(final ICompletionRequest request, final String localName) {
		if (localName == null || request == null) {
			return null;
		}
		DOMNode pluginNode = request.getNode();
		while (!localName.equals(pluginNode.getLocalName())) {
			pluginNode = pluginNode.getParentNode();
		}
		if (localName.equals(pluginNode.getLocalName())) {
			return pluginNode;
		}
		return null;
	}

	private void collectPluginConfigurationElements(ICompletionRequest request, ICompletionResponse response) {
		PluginDescriptor pluginDescriptor = getContainingPluginDescriptor(request);
		if (pluginDescriptor == null) {
			return;
		}
		List<MojoDescriptor> mojosToConsiderList = pluginDescriptor.getMojos();
		DOMNode executionElementDomNode = findClosestParentNode(request, "execution");
		if (executionElementDomNode != null) {
			Set<String> interestingMojos = executionElementDomNode.getChildren().stream()
				.filter(node -> "goals".equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream())
				.filter(node -> "goal".equals(node.getLocalName()))
				.flatMap(node -> node.getChildren().stream())
				.filter(DOMNode::isText)
				.map(DOMNode::getTextContent)
				.collect(Collectors.toSet());
			mojosToConsiderList = mojosToConsiderList.stream().filter(mojo -> interestingMojos.contains(mojo.getGoal())).collect(Collectors.toList());
		}
		List<Parameter> parameters = mojosToConsiderList.stream().flatMap(mojo -> mojo.getParameters().stream()).collect(Collectors.toList());
		collectSimpleCompletionItems(parameters, Parameter::getName, Parameter::getDescription, request, response);
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
		allProps.put("project.build.directory", project.getBuild() == null ? "unknown" : project.getBuild().getDirectory());
		allProps.put("project.build.outputDirectory",
				project.getBuild() == null ? "unknown" : project.getBuild().getOutputDirectory());

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
	
	private void internalCollectRemoteGAVCompletion(ICompletionRequest request, ICompletionResponse response,
			boolean onlyPlugins) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);

		Artifact artifactToSearch = VersionValidator.parseArtifact(node);
		MavenProject project = cache.getLastSuccessfulMavenProject(doc);
		if (project == null) {
			return;
		}
		Collection<CompletionItem> items = Collections.synchronizedSet(new LinkedHashSet<>());
		try {
			CompletableFuture.allOf(project.getRemoteArtifactRepositories().stream().map(repository -> {
				final CompletionItem updatingItem = new CompletionItem("Updating index for " + repository.getUrl());
				updatingItem.setPreselect(true);
				updatingItem.setInsertText("");
				items.add(updatingItem);
				return indexSearcher.getIndexingContext(URI.create(repository.getUrl())).thenAccept(index -> {
					switch (node.getLocalName()) {
					case "groupId":
						if (artifactToSearch.getGroupId().equals(VersionValidator.placeholderArtifact.getGroupId())) {
							// Don't do a remote groupId search if the user hasn't inputed anything as there
							// will be too many results
							return;
						}
						// TODO: just pass only plugins boolean, and make getGroupId's accept a boolean parameter
						if (onlyPlugins) {
							indexSearcher.getPluginGroupIds(artifactToSearch, index).stream()
									.map(groupId -> toCompletionItem(groupId, "GroupId", range)).forEach(items::add);
						} else {
							indexSearcher.getGroupIds(artifactToSearch, index).stream()
									.map(groupId -> toCompletionItem(groupId, "GroupId", range)).forEach(items::add);
						}
						return;
					case "artifactId":
						if (onlyPlugins) {
							indexSearcher.getPluginArtifactIds(artifactToSearch, index).stream()
									.map(artifactInfo -> toCompletionItem(artifactInfo.getArtifactId(),
											artifactInfo.getDescription(), range))
									.forEach(items::add);
						} else {
							indexSearcher.getArtifactIds(artifactToSearch, index).stream()
									.map(artifactInfo -> toCompletionItem(artifactInfo.getArtifactId(),
											artifactInfo.getDescription(), range))
									.forEach(items::add);
						}
						return;
					case "version":
						if (onlyPlugins) {
							indexSearcher.getPluginArtifactVersions(artifactToSearch, index).stream()
									.map(version -> toCompletionItem(version, "Artifact Version", range))
									.forEach(items::add);
						} else {
							indexSearcher.getArtifactVersions(artifactToSearch, index).stream()
									.map(version -> toCompletionItem(version, "Artifact Version", range))
									.forEach(items::add);
						}
						return;
					}
				}).whenComplete((ok, error) -> items.remove(updatingItem));
			}).toArray(CompletableFuture<?>[]::new)).get(2, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException exception) {
			exception.printStackTrace();
		} catch (TimeoutException e) {
			// nothing to log, some work still pending
		}
		items.forEach(response::addCompletionItem);
	}

	private void collectRemoteGAVCompletion(ICompletionRequest request, ICompletionResponse response) {
		internalCollectRemoteGAVCompletion(request, response, false);
	}
	
	private void collectRemotePluginGAVCompletion(ICompletionRequest request, ICompletionResponse response) {
		internalCollectRemoteGAVCompletion(request, response, true);
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
		if (description != null) {
			item.setDocumentation(Either.forLeft(description));
		}
		item.setFilterText(insertText);
		item.setInsertTextFormat(InsertTextFormat.PlainText);
		item.setTextEdit(new TextEdit(range, insertText));
		return item;
	}
}
