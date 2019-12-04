package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class LocalSubModuleSearcher {
	//TODO: Maybe this class shouldn't be a singleton, but instead a field instance in ArtifactSearcherManager?
	Model model;
	MavenXpp3Reader mavenreader = new MavenXpp3Reader();
	private static final LocalSubModuleSearcher INSTANCE = new LocalSubModuleSearcher();

	private LocalSubModuleSearcher() {

	}
	
	public static LocalSubModuleSearcher getInstance() {
		return INSTANCE;
	}

	public void setPomFile(File pomFile) throws FileNotFoundException, IOException, XmlPullParserException {
		model = mavenreader.read(new FileReader(pomFile));
	}

	public List<String> getSubModules() {
		return (List<String>) model.getModules();
	}

}
