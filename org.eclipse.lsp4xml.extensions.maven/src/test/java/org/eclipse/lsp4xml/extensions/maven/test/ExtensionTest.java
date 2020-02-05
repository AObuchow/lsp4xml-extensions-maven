/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4xml.extensions.maven.test;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExtensionTest {

	private ClientServerConnection connection;

	@Before
	public void setUp() throws IOException {
		connection = new ClientServerConnection();
	}

	@After
	public void tearDown() {
		connection.stop();
	}

	@Test(timeout=45000)
	public void testRemoteGroupIdCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-groupId-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Position pos = new Position(11, 19);
		String desiredCompletion = "com.google.android";
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get();
		List<CompletionItem> items = completion.getRight().getItems();
		while (completionContains(items, "Updating Maven repository index...")
				&& !(completionContains(items, desiredCompletion) | completionContains(items, "Error") | completionContains(items, "No artifact versions found.") )) {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		}
		items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		assertTrue(completionContains(items, desiredCompletion));
	}
	
	@Test(timeout=45000)
	public void testRemoteArtifactIdCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-artifactId-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Position pos = new Position(12, 15);
		String desiredCompletion = "android";
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get();
		List<CompletionItem> items = completion.getRight().getItems();
		while (completionContains(items, "Updating Maven repository index...")
				&& !(completionContains(items, desiredCompletion) | completionContains(items, "Error") | completionContains(items, "No artifact versions found.") )) {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		}
		items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		assertTrue(completionContains(items, desiredCompletion));
	}
	

	@Test(timeout=45000)
	public void testRemoteVersionCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-remote-version-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Position pos = new Position(13, 13);
		String desiredCompletion = "4.1.1.4";
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get();
		List<CompletionItem> items = completion.getRight().getItems();
		while (completionContains(items, "Updating Maven repository index...")
				&& !(completionContains(items, desiredCompletion) | completionContains(items, "Error") | completionContains(items, "No artifact versions found.") )) {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		}
		items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), pos)).get().getRight().getItems();
		assertTrue(completionContains(items, desiredCompletion));
	}

	@Test(timeout=45000)
	public void testRemoteVersionCompletionNoResults() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-version-complete-no-results.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), new Position(13, 13))).get();
		List<CompletionItem> items = completion.getRight().getItems();
		while (completionContains(items, "Updating Maven repository index...")
				&& !(completionContains(items, "No artifact versions found.") | completionContains(items, "Error"))) {
			 items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), new Position(13, 13))).get().getRight().getItems();
		}
		items = connection.languageServer.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()), new Position(13, 13))).get().getRight().getItems();
		assertTrue(completionContains(items, "No artifact versions found."));
	}
	
	@Test(timeout=15000)
	public void testScopeCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-module-error.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(12, 10)))
				.get();
		assertTrue(completion.getRight().getItems().stream().map(CompletionItem::getLabel).anyMatch("runtime"::equals));
	}


	@Test(timeout=15000)
	public void testPropertyCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-properties.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(11, 15)))
				.get();
		List<CompletionItem> items = completion.getRight().getItems();
		assertTrue(items.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("myProperty")));
		assertTrue(items.stream().map(CompletionItem::getLabel)
				.anyMatch(label -> label.contains("project.build.directory")));
	}


	@Test(timeout=15000)
	public void testParentPropertyCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-properties-in-parent.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(15, 20)))
				.get();
		List<CompletionItem> items = completion.getRight().getItems();
		assertTrue(items.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("myProperty")));
	}

	@Test(timeout=20000)
	public void testLocalParentGAVCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, TimeoutException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-local-parent-complete.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		List<CompletionItem> items = Collections.emptyList(); 
		
		items = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(8, 12)))
				.get(10000, TimeUnit.MILLISECONDS).getRight().getItems();

		assertTrue(completionContains(items, "0.0.1-SNAPSHOT"));
		
		items = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(7, 15)))
				.get(10000, TimeUnit.MILLISECONDS).getRight().getItems();

		assertTrue(completionContains(items, "test"));

		items = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(6, 12)))
				.get(10000, TimeUnit.MILLISECONDS).getRight().getItems();

		assertTrue(completionContains(items, "org.test"));
	}


	@Test
	public void testMissingArtifactIdError()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-without-artifactId.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		assertTrue(connection.waitForDiagnostics(diagnostics -> diagnostics.stream().map(Diagnostic::getMessage)
				.anyMatch(message -> message.contains("artifactId")), 15000));
		DidChangeTextDocumentParams didChange = new DidChangeTextDocumentParams();
		didChange.setTextDocument(new VersionedTextDocumentIdentifier(textDocumentItem.getUri(), 2));
		didChange.setContentChanges(Collections.singletonList(new TextDocumentContentChangeEvent(
				new Range(new Position(5, 28), new Position(5, 28)), 0, "<artifactId>a</artifactId>")));
		connection.languageServer.getTextDocumentService().didChange(didChange);
		assertTrue(connection.waitForDiagnostics(Collection<Diagnostic>::isEmpty, 20000));
	}


	@Test(timeout=15000)
	public void testCompleteDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/pom-with-dependency.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
				.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
						new Position(11, 7)))
				.get();
		List<CompletionItem> items = completion.getRight().getItems();
		Optional<String> mavenCoreCompletionItem = items.stream().map(CompletionItem::getLabel)
				.filter(label -> label.contains("org.apache.maven:maven-core")).findAny();
		assertTrue(mavenCoreCompletionItem.isPresent());
	}


	@Test(timeout=15000)
	public void testCompleteScope() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/scope.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		{
			Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
					.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
							new Position(0, 7)))
					.get();
			List<CompletionItem> items = completion.getRight().getItems();
			assertTrue(items.stream().map(CompletionItem::getTextEdit).map(TextEdit::getNewText)
					.anyMatch("compile"::equals));
		}
		{
			Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
					.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
							new Position(1, 7)))
					.get();
			List<CompletionItem> items = completion.getRight().getItems();
			assertTrue(items.stream().map(CompletionItem::getTextEdit).map(TextEdit::getNewText)
					.anyMatch("compile</scope>"::equals));
		}
	}


	@Test(timeout=15000)
	public void testCompletePhase() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocumentItem textDocumentItem = createTextDocumentItem("/phase.xml");
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(textDocumentItem);
		connection.languageServer.getTextDocumentService().didOpen(params);
		{
			Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
					.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
							new Position(0, 7)))
					.get();
			List<CompletionItem> items = completion.getRight().getItems();
			assertTrue(items.stream().map(CompletionItem::getTextEdit).map(TextEdit::getNewText)
					.anyMatch("generate-resources"::equals));
		}
		{
			Either<List<CompletionItem>, CompletionList> completion = connection.languageServer.getTextDocumentService()
					.completion(new CompletionParams(new TextDocumentIdentifier(textDocumentItem.getUri()),
							new Position(1, 7)))
					.get();
			List<CompletionItem> items = completion.getRight().getItems();
			assertTrue(items.stream().map(CompletionItem::getTextEdit).map(TextEdit::getNewText)
					.anyMatch("generate-resources</phase>"::equals));
		}
	}

	TextDocumentItem createTextDocumentItem(String resourcePath) throws IOException, URISyntaxException {
		URI uri = getClass().getResource(resourcePath).toURI();
		File file = new File(uri);
		return new TextDocumentItem(uri.toString(), "xml", 1, new String(Files.readAllBytes(file.toPath())));
	}

	boolean completionContains(List<CompletionItem> completionItems, String searchString) {
		return completionItems.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains(searchString));
	}

}
