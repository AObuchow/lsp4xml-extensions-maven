/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven;

import org.apache.maven.model.Dependency;
import org.eclipse.lsp4xml.dom.DOMNode;

public class MavenParseUtils {

	public static Dependency parseArtifact(DOMNode node) {
		Dependency res = new Dependency();
		if (node == null) {
			return res;
		}
		try {
			for (DOMNode tag : node.getParentElement().getChildren()) {
				if (tag != null && tag.hasChildNodes()) {
					String value = tag.getChild(0).getNodeValue();
					if (value == null) {
						continue;
					}
					value = value.trim(); 
					switch (tag.getLocalName()) {
					case "groupId":
						res.setGroupId(value);
						break;
					case "artifactId":
						res.setArtifactId(value);
						break;
					case "version":
						res.setVersion(value);
						break;
					case "scope":
						res.setScope(value);
						break;
					case "type":
						res.setType(value);
						break;
					case "classifier":
						res.setClassifier(value);
						break;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Error parsing Artifact");
		}
		return res;
	}
}
