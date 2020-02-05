package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.Version;
import org.eclipse.lsp4xml.extensions.maven.MavenPlugin;
import org.eclipse.lsp4xml.extensions.maven.MavenRepositoryCache;

public class RemoteRepositoryIndexSearcher {
	public static final int COMPARISON_TYPE_GREATER = 1; // 0001
	public static final int COMPARISON_TYPE_LESS = 2; // 0010
	public static final int COMPARISON_TYPE_EQUALS = 4; // 0100

	private Indexer indexer;

	private IndexUpdater indexUpdater;

	private List<IndexCreator> indexers = new ArrayList<>();

	private String indexPath;

	private Wagon httpWagon;

	private HashMap<String, IndexingContext> indexingContexts = new HashMap<>();

	private Set<String> artifactVersions = Collections.synchronizedSet(new HashSet<String>());

	private Map<String, String> artifactIds = new ConcurrentHashMap<>();

	private Set<String> groupIds = Collections.synchronizedSet(new HashSet<String>());

	private CompletableFuture<Void> syncRequest;

	private Set<String> brokenContexts = Collections.synchronizedSet(new HashSet<String>());

	public RemoteRepositoryIndexSearcher(PlexusContainer plexusContainer) throws ComponentLookupException {
		if (syncRequest == null) {
			indexer = plexusContainer.lookup(Indexer.class);
			indexUpdater = plexusContainer.lookup(IndexUpdater.class);
			httpWagon = plexusContainer.lookup(Wagon.class, "http");
			indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));
			String pathSeperator = System.getProperty("file.separator");
			this.indexPath = MavenPlugin.DEFAULT_LOCAL_REPOSITORY_PATH + pathSeperator + "_maven_index_"
					+ pathSeperator;

			syncRequest = syncIndex();
		}
	}

	/**
	 * Returns a Maven index sync request in a {@code CompletableFuture}. The
	 * {@code CompletableFuture} must be run for the Maven indexer to work
	 * correctly.
	 * 
	 * @return {@code CompletableFuture<Void>}syncRequest - must be run in order to
	 *         update the Maven index before use
	 */
	public CompletableFuture<Void> getSyncRequest() {
		return syncRequest;
	}

	public CompletableFuture<Void> syncIndex() {
		try {
			boolean contextInitialized = true;
			for (RemoteRepository repo : MavenRepositoryCache.getInstance().getRemoteRepositories()) {
				if (!indexingContexts.containsKey(repo.getId()) && !brokenContexts.contains(repo.getId())) {
					contextInitialized = false;
				}
			}

			if (!contextInitialized) {
				initializeContext();
				// TODO: Index should be updated if context isin't initialized, as a new
				// repository might have been added
			}
			return updateIndex().handleAsync((a, b) -> {
				if (b != null) {
					b.printStackTrace();
				}
				return a;
			});

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public CompletableFuture<Set<String>> getArtifactVersions(Artifact artifactToSearch) {
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.EXACT);
		final Query artifactIdQ = indexer.constructQuery(MAVEN.ARTIFACT_ID, artifactToSearch.getArtifactId(),
				SearchType.EXACT);
		final Query jarPackagingQ = indexer.constructQuery(MAVEN.PACKAGING, "jar", SearchType.EXACT);

		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(artifactIdQ, Occur.SHOULD)
				.add(jarPackagingQ, Occur.MUST).build();

		List<IndexingContext> contexts = new ArrayList<>();
		contexts.addAll(indexingContexts.values());
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);

		final CompletableFuture<List<ArtifactInfo>> artifactInfoResults = createIndexerQuery(artifactToSearch, request);

		return artifactInfoResults.thenApply(artifactInfos -> {
			artifactVersions.clear();
			if (!artifactInfos.isEmpty()) {
				artifactInfos.forEach(info -> artifactVersions.add(info.getVersion()));
			} else {
				String noResults = "No artifact versions found.";
				artifactVersions.add(noResults);
			}
			return artifactVersions;
		});
	}

	/**
	 * @param artifactToSearch a CompletableFuture containing a
	 *                         {@code Map<String artifactId, String artifactDescription>}
	 * @return
	 */
	public CompletableFuture<Map<String, String>> getArtifactIds(Artifact artifactToSearch) {
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.SCORED);
		final Query jarPackagingQ = indexer.constructQuery(MAVEN.PACKAGING, "jar", SearchType.EXACT);
		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(jarPackagingQ, Occur.MUST)
				.build();
		List<IndexingContext> contexts = new ArrayList<>();
		contexts.addAll(indexingContexts.values());
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);

		final CompletableFuture<List<ArtifactInfo>> artifactInfoResults = createIndexerQuery(artifactToSearch, request);

		return artifactInfoResults.thenApply(artifactInfos -> {
			artifactIds.clear();
			if (!artifactInfos.isEmpty()) {
				artifactInfos.forEach(info -> artifactIds.put(info.getArtifactId(),
						info.getDescription() != null ? info.getDescription() : "No description found."));
			} else {
				String noResults = "No artifacts found.";
				artifactIds.put(noResults, "");
			}
			return artifactIds;
		});
	}

	// TODO: Get groupid description for completion
	public CompletableFuture<Set<String>> getGroupIds(Artifact artifactToSearch) {
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.SCORED);
		final Query jarPackagingQ = indexer.constructQuery(MAVEN.PACKAGING, "jar", SearchType.EXACT);
		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(jarPackagingQ, Occur.MUST)
				.build();
		List<IndexingContext> contexts = new ArrayList<>();
		contexts.addAll(indexingContexts.values());
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);
		// TODO: Find the Count sweet spot
		request.setCount(7500);

		final CompletableFuture<List<ArtifactInfo>> artifactInfoResults = createIndexerQuery(artifactToSearch, request);

		return artifactInfoResults.thenApply(artifactInfos -> {
			groupIds.clear();
			if (!artifactInfos.isEmpty()) {
				artifactInfos.forEach(info -> groupIds.add(info.getGroupId()));
			} else {
				String noResults = "No groupId's found.";
				groupIds.add(noResults);
			}
			return groupIds;
		});
	}

	// TODO: Remove print statements? Maybe send notices over JSON RPC?
	TransferListener listener = new AbstractTransferListener() {
		@Override
		public void transferStarted(TransferEvent transferEvent) {
			System.out.println("Downloading " + transferEvent.getResource().getName());
		}

		@Override
		public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
		}

		@Override
		public void transferCompleted(TransferEvent transferEvent) {
			System.out.println("Done downloading " + transferEvent.getResource().getName());
		}
	};

	private CompletableFuture<Void> updateIndex() {
		System.out.println("Updating Index...");
		ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, null, null);

		indexingContexts.keySet().removeIf(repoID -> brokenContexts.contains(repoID));
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (Entry<String, IndexingContext> context : indexingContexts.entrySet()) {
			Date contextCurrentTimestamp = context.getValue().getTimestamp();
			IndexUpdateRequest updateRequest = new IndexUpdateRequest(context.getValue(), resourceFetcher);
			try {
				if (context.getValue().getIndexWriter() != null) {
					final CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
						try {
							IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
							if (updateResult.isFullUpdate()) {
								System.out.println("Full update happened!");
							} else if (updateResult.getTimestamp().equals(contextCurrentTimestamp)) {
								System.out.println("No update needed, index is up to date!");
							} else {
								System.out.println("Incremental update happened, change covered "
										+ contextCurrentTimestamp + " - " + updateResult.getTimestamp() + " period.");
							}

						} catch (IOException e) {
							// TODO: Fix this - the maven central context gets reported as broken when
							// another context is broken
							brokenContexts.add(context.getKey());
							System.out.println("Invalid Context: " + context.getValue().getRepositoryId() + " @ "
									+ context.getValue().getRepositoryUrl());
							e.printStackTrace();
							// TODO: Maybe scan for maven metadata to use as an alternative to retrieve GAV
						}
					});
					futures.add(future);
				}
				else {
					System.out.println("INDEX WRITER IS NULL");
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
	}

	private void initializeContext() throws IOException {
		File targetDirectory = new File(indexPath);
		if (!targetDirectory.exists()) {
			targetDirectory.mkdirs();
		}

		for (RemoteRepository repo : MavenRepositoryCache.getInstance().getRemoteRepositories()) {
			File repoFile = new File(indexPath + repo.getId() + "-cache");
			File repoIndex = new File(indexPath + repo.getId() + "-index");
			try {
				IndexingContext context = indexer.createIndexingContext(repo.getId() + "-context", repo.getId(),
						repoFile, repoIndex, repo.getUrl(), null, true, true, indexers);
				indexingContexts.put(repo.getId(), context);
			} catch (Exception e) {
				e.printStackTrace();
			}

		}

	}

	public void closeContext() {
		for (IndexingContext context : indexingContexts.values()) {
			try {
				indexer.closeIndexingContext(context, false);
			} catch (IOException e) {
				System.out.println("Warning - could not close context: " + context.getId());
				e.printStackTrace();
			}
		}

	}

//////////////////////////////////// Helper Methods ///////////////////////////////////////////////

	private CompletableFuture<List<ArtifactInfo>> createIndexerQuery(Artifact artifactToSearch,
			final IteratorSearchRequest request) {
		return CompletableFuture.supplyAsync(() -> {
			IteratorSearchResponse response = null;
			try {
				response = indexer.searchIterator(request);
			} catch (IOException e) {
				System.out.println("Index search failed for " + String.join(":", artifactToSearch.getGroupId(),
						artifactToSearch.getArtifactId(), artifactToSearch.getVersion()));
				e.printStackTrace();
			}
			List<ArtifactInfo> artifactInfos = new ArrayList<>();
			if (response != null) {
				response.getResults().forEach(artifactInfos::add);
			}
			return artifactInfos;
		});
	}

	private ArtifactInfoFilter createVersionFilter(String versionToCompare, int comparisonType) {
		final GenericVersionScheme versionScheme = new GenericVersionScheme();

		return (ctx, ai) -> {
			try {
				final Version aiV = versionScheme.parseVersion(ai.getVersion());
				final Version version = versionScheme.parseVersion(versionToCompare);
				int comparisonResult = aiV.compareTo(version);

				switch (comparisonType) {
				case (RemoteRepositoryIndexSearcher.COMPARISON_TYPE_EQUALS):
					return comparisonResult == 0;
				case (RemoteRepositoryIndexSearcher.COMPARISON_TYPE_GREATER):
					return comparisonResult >= 0;
				case (RemoteRepositoryIndexSearcher.COMPARISON_TYPE_LESS):
					return comparisonResult <= 0;
				default:
					// should never get here
					throw new IllegalArgumentException(
							"comparisonType argument must be one of COMPARISON_TYPE_EQUALS COMPARISON_TYPE_GREATER COMPARISON_TYPE_LESS");
				}

			} catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
				e.printStackTrace();
				return false;
			}
		};
	}
}
