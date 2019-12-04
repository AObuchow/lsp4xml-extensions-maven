package org.eclipse.lsp4xml.extensions.maven;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
/**
 * 
 * @author azerr
 * @see https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
 */
public enum DependencyScope {

	compile(org.apache.maven.artifact.Artifact.SCOPE_COMPILE,"THIS IS DIFFERENT"), 
	provided(org.apache.maven.artifact.Artifact.SCOPE_PROVIDED,
			"This is much like compile, but indicates you expect the JDK or a container to provide the dependency at runtime. For example, when building a web application for the Java Enterprise Edition, you would set the dependency on the Servlet API and related Java EE APIs to scope provided because the web container provides those classes. This scope is only available on the compilation and test classpath, and is not transitive."),
	runtime(org.apache.maven.artifact.Artifact.SCOPE_RUNTIME, "This scope indicates that the dependency is not required for compilation, but is for execution. It is in the runtime and test classpaths, but not the compile classpath."),
	test(org.apache.maven.artifact.Artifact.SCOPE_TEST,"This scope indicates that the dependency is not required for normal use of the application, and is only available for the test compilation and execution phases. This scope is not transitive."),
	system(org.apache.maven.artifact.Artifact.SCOPE_SYSTEM,"This scope is similar to provided except that you have to provide the JAR which contains it explicitly. The artifact is always available and is not looked up in a repository."),
	importScope(org.apache.maven.artifact.Artifact.SCOPE_IMPORT,
			"This scope is only supported on a dependency of type pom in the <dependencyManagement> section. It indicates the dependency to be replaced with the effective list of dependencies in the specified POM's <dependencyManagement> section. Since they are replaced, dependencies with a scope of import do not actually participate in limiting the transitivity of a dependency.");

	private final String name;
	//"This is the default scope, used if none is specified. Compile dependencies are available in all classpaths of a project. Furthermore, those dependencies are propagated to dependent projects."

	private final String description;

	private DependencyScope(String description) {
		this(null, description);
	}

	private DependencyScope(String name, String description) {
		this.name = name != null ? name : name();
		this.description = description;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}
}
