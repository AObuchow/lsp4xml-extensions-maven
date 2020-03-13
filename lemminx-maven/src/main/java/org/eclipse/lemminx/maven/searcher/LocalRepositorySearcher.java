/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class LocalRepositorySearcher {
	
	public static final class GroupIdArtifactId {
		public final String groupId;
		public final String artifactId;
		
		public GroupIdArtifactId(String groupId, String artifactId) {
			this.groupId = groupId;
			this.artifactId = artifactId;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null &&
				obj instanceof GroupIdArtifactId &&
				Objects.equals(this.groupId, ((GroupIdArtifactId)obj).groupId) &&
				Objects.equals(this.artifactId, ((GroupIdArtifactId)obj).artifactId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.groupId, this.artifactId);
		}
	}
	
	private File localRepository;

	public LocalRepositorySearcher(File localRepository) {
		this.localRepository = localRepository;
		
	}

	private Map<File, Map<GroupIdArtifactId, ArtifactVersion>> cache = new HashMap<>();

	public Set<String> searchGroupIds() throws IOException {
		return getLocalArtifactsLastVersion().keySet().stream().map(ga -> ga.groupId).distinct().collect(Collectors.toSet());
	}

	public Set<String> searchPluginGroupIds() throws IOException {
		return getLocalPluginArtifacts().keySet().stream().map(ga -> ga.groupId).distinct().collect(Collectors.toSet());
	}

	public Map<GroupIdArtifactId, ArtifactVersion> getLocalPluginArtifacts() throws IOException {
		return getLocalArtifactsLastVersion().entrySet().stream().filter(entry -> entry.getKey().artifactId.contains("-plugin")).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
	}

	public Map<GroupIdArtifactId, ArtifactVersion> getLocalArtifactsLastVersion() throws IOException {
		Map<GroupIdArtifactId, ArtifactVersion> res = cache.get(localRepository);
		if (res == null) {
			res = computeLocalArtifacts();
			Path localRepoPath = localRepository.toPath();
			WatchService watchService = localRepoPath.getFileSystem().newWatchService();
			localRepoPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
			new Thread(() -> {
				WatchKey key;
				try {
					while ((key = watchService.take()) != null) {
						cache.remove(localRepository);
						key.reset();
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}).start();
			cache.put(localRepository, res);
		}
		return res;
	}

	public Map<GroupIdArtifactId, ArtifactVersion> computeLocalArtifacts() throws IOException {
		final Path repoPath = localRepository.toPath();
		Map<GroupIdArtifactId, ArtifactVersion> groupIdArtifactIdToVersion = new HashMap<>();
		Files.walkFileTree(repoPath, Collections.emptySet(), 10, new SimpleFileVisitor<Path>() { 
			@Override
			public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.getFileName().toString().charAt(0) == '.') {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (Character.isDigit(file.getFileName().toString().charAt(0))) {
					Path artifactFolderPath = repoPath.relativize(file);
					ArtifactVersion version = new DefaultArtifactVersion(artifactFolderPath.getFileName().toString());
					String artifactId = artifactFolderPath.getParent().getFileName().toString();
					String groupId = artifactFolderPath.getParent().getParent().toString().replace(artifactFolderPath.getFileSystem().getSeparator(), ".");
					GroupIdArtifactId groupIdArtifactId = new GroupIdArtifactId(groupId, artifactId);
					ArtifactVersion existingVersion = groupIdArtifactIdToVersion.get(groupIdArtifactId);
					if (existingVersion == null || existingVersion.compareTo(version) < 0 || (!version.toString().endsWith("-SNAPSHOT") && existingVersion.toString().endsWith("-SNAPSHOT"))) {
						groupIdArtifactIdToVersion.put(groupIdArtifactId, version);
					}
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return groupIdArtifactIdToVersion;
	}

}