package org.eclipse.lsp4xml.extensions.maven.searcher;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class LocalPropertiesSearcher {
	Model model;
	MavenXpp3Reader mavenreader = new MavenXpp3Reader();
	private static final LocalPropertiesSearcher INSTANCE = new LocalPropertiesSearcher();
	private LocalPropertiesSearcher() {
	}
	public static LocalPropertiesSearcher getInstance() {
		return INSTANCE;
	}
	
	public void setPomFile(File pomFile) throws FileNotFoundException, IOException, XmlPullParserException {
		model = mavenreader.read(new FileReader(pomFile));
	}
	
	public List<String> getProperties(){
		Properties properties = model.getProperties();
		return Arrays.asList(properties.keySet().toArray()).stream().map(Object::toString).collect(Collectors.toList());
	}

}
