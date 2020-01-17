package org.eclipse.lsp4xml.extensions.maven;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.lsp4xml.dom.DOMDocument;

public class RepositoryCache {
	List<RemoteRepository> repos = new ArrayList<>();

	private static final RepositoryCache INSTANCE = new RepositoryCache();

	private RepositoryCache() {
		repos.add(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()); // Default
																													// maven
																													// repository
	}

	// TODO: This should use a cached maven model or use a working copy at least as
	// it might get out of sync
	public void update(DOMDocument xmlDocument) {
		Model model;
		MavenXpp3Reader mavenreader = new MavenXpp3Reader();
		File pomFile = new File(URI.create(xmlDocument.getDocumentURI()));
		try {
			model = mavenreader.read(new FileReader(pomFile));
			// TODO: Verify repo.getLayout is correct
			repos.addAll(model.getRepositories().stream()
					.map(repo -> new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl()).build())
					.distinct().collect(Collectors.toList()));
			// TODO: Traverse parent hierarchy and add all it's repos, currently model.getParent returns "../pom.xml"
			Deque<Parent> parentHierarchy = new ArrayDeque<>();
			if (model.getParent() != null) {
				parentHierarchy.add(model.getParent());
				while (!parentHierarchy.isEmpty()) {
					Parent currentParent = parentHierarchy.pop();
					Model parentModel = mavenreader.read(new FileReader(new File(currentParent.getRelativePath())));
					repos.addAll(parentModel.getRepositories().stream().map(
							repo -> new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl()).build())
							.distinct().collect(Collectors.toList()));
					parentHierarchy.add(parentModel.getParent());
				}
			}

			// TODO: get repositories from user and global maven settings
		} catch (IOException | XmlPullParserException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public List<RemoteRepository> getRemoteRepositories() {
		return this.repos;
	}

	public static RepositoryCache getInstance() {
		return INSTANCE;
	}
}
