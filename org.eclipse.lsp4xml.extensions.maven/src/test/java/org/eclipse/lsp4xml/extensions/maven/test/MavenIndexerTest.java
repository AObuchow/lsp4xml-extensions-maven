package org.eclipse.lsp4xml.extensions.maven.test;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.lsp4xml.extensions.maven.MavenPlugin;
import org.eclipse.lsp4xml.extensions.maven.searcher.RemoteRepositoryIndexSearcher;
import org.junit.After;
import org.junit.Test;

public class MavenIndexerTest {
	
	private RemoteRepositoryIndexSearcher indexSearcher;

	@After
	public void tearDown() {
		indexSearcher.closeContext();
		indexSearcher = null;
	}

	@Test(timeout= 600000)
	public void testMavenIndexDownload()
			throws PlexusContainerException, ComponentLookupException, InterruptedException, ExecutionException {
		indexSearcher = new RemoteRepositoryIndexSearcher(MavenPlugin.newPlexusContainer());
		
		CompletableFuture<Void> syncRequest = indexSearcher.getSyncRequest();
		syncRequest.get();
		assertTrue(!syncRequest.isCompletedExceptionally());
		String pathSeperator = System.getProperty("file.separator");
		String indexPath = MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH + pathSeperator + "_maven_index_" + pathSeperator;
		File indexFolder = new File(indexPath);
		assertTrue(indexFolder.exists());
		assertTrue(indexFolder.listFiles() != null);
		for (File index : indexFolder.listFiles()) {
			if (index.getName().equals("central-index")) {
				assertTrue(index.listFiles() != null);
				boolean propertiesExist = false;
				String propertiesFile = "nexus-maven-repository-index-updater.properties";
				for (File file : index.listFiles()) {
					if (file.getName().equals(propertiesFile)) {
						propertiesExist = true;
					}
				}
				assertTrue(propertiesExist);
			}
		}
	}

}
