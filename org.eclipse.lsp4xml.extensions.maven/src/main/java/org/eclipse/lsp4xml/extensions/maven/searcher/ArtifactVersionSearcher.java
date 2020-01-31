package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.Field;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
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

public class ArtifactVersionSearcher {
	public static final int COMPARISON_TYPE_GREATER = 1; // 0001
	public static final int COMPARISON_TYPE_LESS = 2; // 0010
	public static final int COMPARISON_TYPE_EQUALS = 4; // 0100

	private static final ArtifactVersionSearcher INSTANCE = new ArtifactVersionSearcher();

	private Indexer indexer;

	private IndexUpdater indexUpdater;

	private List<IndexCreator> indexers = new ArrayList<>();

	private String indexPath;

	private Wagon httpWagon;

	private HashMap<String, IndexingContext> indexingContexts = new HashMap<>();

	private List<String> artifactVersions = Collections.synchronizedList(new ArrayList<>());

	private CompletableFuture<Void> syncRequest;

	private Set<String> brokenContexts = Collections.synchronizedSet(new HashSet<String>());

	private ArtifactVersionSearcher() {
	}

	public static ArtifactVersionSearcher getInstance() {
		return INSTANCE;
	}

	/**
	 * Returns a Maven index sync request in a CompletableFuture. This function must
	 * be called before this class can be used, and the returned CompletableFuture
	 * must be run for the maven indexer to work correctly.
	 * 
	 * @param plexusContainer
	 * @return CompletableFuture<Void> syncRequest - must be run in order to update
	 *         the Maven index before use
	 * @throws ComponentLookupException
	 */
	public CompletableFuture<Void> init(PlexusContainer plexusContainer) throws ComponentLookupException {
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
		return syncRequest;
	}

	public CompletableFuture<List<String>> getArtifactVersions(Artifact artifactToSearch) {
		final BooleanQuery query = createArtifactQuery(artifactToSearch);

		final ArtifactInfoFilter versionFilter = createVersionFilter("0.0.0",
				ArtifactVersionSearcher.COMPARISON_TYPE_GREATER);
		List<IndexingContext> contexts = new ArrayList<>();
		contexts.addAll(indexingContexts.values());
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, versionFilter);

		final CompletableFuture<List<ArtifactInfo>> getArtifactVersions = CompletableFuture.supplyAsync(() -> {
			IteratorSearchResponse response = null;
			try {
				response = indexer.searchIterator(request);
			} catch (IOException e) {
				System.out.println("Index search failed for " + String.join(":", artifactToSearch.getGroupId(), artifactToSearch.getArtifactId(), artifactToSearch.getVersion()));
				e.printStackTrace();
			}
			List<ArtifactInfo> artifactInfos = new ArrayList<>();
			response.getResults().forEach(artifactInfos::add);
			return artifactInfos;
		});

		return getArtifactVersions.thenApply(artifactInfos -> {
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

	private ArtifactInfoFilter createVersionFilter(String versionToCompare, int comparisonType) {
		final GenericVersionScheme versionScheme = new GenericVersionScheme();

		return (ctx, ai) -> {
			try {
				final Version aiV = versionScheme.parseVersion(ai.getVersion());
				final Version version = versionScheme.parseVersion(versionToCompare);
				int comparisonResult = aiV.compareTo(version);

				switch (comparisonType) {
				case (ArtifactVersionSearcher.COMPARISON_TYPE_EQUALS):
					return comparisonResult == 0;
				case (ArtifactVersionSearcher.COMPARISON_TYPE_GREATER):
					return comparisonResult >= 0;
				case (ArtifactVersionSearcher.COMPARISON_TYPE_LESS):
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

	// TODO: Turn this into a factory, which lets caller specify if
	// groupId/artifactId/version must occur or not
	private BooleanQuery createArtifactQuery(Artifact artifactToSearch) {
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.EXACT);

		final Query artifactIdQ = indexer.constructQuery(MAVEN.ARTIFACT_ID, artifactToSearch.getArtifactId(),
				SearchType.EXACT);

		return new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(artifactIdQ, Occur.MUST)
				.add(indexer.constructQuery(MAVEN.PACKAGING, "jar", SearchType.EXACT), Occur.MUST)
				// Note: this below is unfinished API, needs fixing
				.add(indexer.constructQuery(MAVEN.CLASSIFIER, Field.NOT_PRESENT, SearchType.EXACT), Occur.MUST_NOT)
				.build();
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

	// TODO: Remove print statements? Maybe send notices over JSON RPC?
	TransferListener listener = new AbstractTransferListener() {
		public void transferStarted(TransferEvent transferEvent) {
			System.out.println("Downloading " + transferEvent.getResource().getName());
		}

		public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
		}

		public void transferCompleted(TransferEvent transferEvent) {
			System.out.println("Done downloading " + transferEvent.getResource().getName());
		}
	};

	private CompletableFuture<Void> updateIndex() throws IOException {
		System.out.println("Updating Index...");
		org.apache.maven.index.updater.ResourceFetcher resourceFetcher = new org.apache.maven.index.updater.WagonHelper.WagonFetcher(httpWagon, listener, null, null);

		indexingContexts.keySet().removeIf(repoID -> brokenContexts.contains(repoID));
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (Entry<String, IndexingContext> context : indexingContexts.entrySet()) {
			Date contextCurrentTimestamp = context.getValue().getTimestamp();
			IndexUpdateRequest updateRequest = new IndexUpdateRequest(context.getValue(), resourceFetcher);
			final CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {

				try {
					IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
					if (updateResult.isFullUpdate()) {
						System.out.println("Full update happened!");
					} else if (updateResult.getTimestamp().equals(contextCurrentTimestamp)) {
						System.out.println("No update needed, index is up to date!");
					} else {
						System.out.println("Incremental update happened, change covered " + contextCurrentTimestamp
								+ " - " + updateResult.getTimestamp() + " period.");
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

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
	}

	private void initializeContext()
			throws ComponentLookupException, IOException, ExistingLuceneIndexMismatchException {
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

}
